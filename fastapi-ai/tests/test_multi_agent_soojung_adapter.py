from app.multi_agent.erp.soojung_adapter import (
    analyze_soojung_erp_node,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


def test_adapts_soojung_erp_agent_result():
    request = createCobaltRequest()

    result = analyze_soojung_erp_node(
        {
            "erp_context": request.model_dump(
                mode="json",
            ),
        }
    )

    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 100
    assert assessment["exposure_level"] == "critical"
    assert assessment["stockout_before_eta"] is True
    assert (
        assessment["has_alternative_supplier"]
        is False
    )
    assert assessment["inventory_days"] == 6.5
    assert assessment["safety_stock_days"] == 18
    assert assessment["next_inbound_eta_days"] == 15
    assert (
        assessment["expected_supply_gap_days"]
        == 8.5
    )
    assert assessment["manual_review_required"] is False
    assert assessment["forced_critical"] is True

    assert result["affected_contract_ids"] == [
        "CTR-010",
    ]
    assert len(
        result["questions_for_contract_agent"]
    ) == 5
    assert assessment["findings"]
    assert result["erp_exposure_response"][
        "requestId"
    ] == "ERP-REQ-004"


def test_preserves_calculation_evidence():
    request = createCobaltRequest()

    result = analyze_soojung_erp_node(
        {
            "erp_context": request.model_dump(
                mode="json",
            ),
        }
    )

    assessment = result["erp_assessment"]

    assert assessment["calculation_evidence"]
    assert assessment["risk_components"]
    assert any(
        "계산식:" in finding
        for finding in assessment["findings"]
    )


def test_missing_erp_context_routes_to_manual_review():
    result = analyze_soojung_erp_node({})

    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 0
    assert (
        assessment["manual_review_required"]
        is True
    )
    assert assessment["findings"] == [
        "ERP 요청 데이터가 없습니다.",
    ]
    assert result["questions_for_contract_agent"] == []
    assert result["affected_contract_ids"] == []