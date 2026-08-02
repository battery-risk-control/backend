from app.multi_agent.erp.soojung_adapter import (
    analyze_soojung_erp_node,
    recheck_soojung_erp_node,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


def _erp_state_after_initial_run():
    """1차 ERP 계산까지 마친 state를 만든다(협상 라운드 테스트의 출발점)."""

    request = createCobaltRequest()
    erp_result = analyze_soojung_erp_node(
        {
            "erp_context": request.model_dump(
                mode="json",
            ),
        }
    )

    return {
        **erp_result,
        "questions_for_erp_agent": [
            "검색된 계약 근거를 반영한 실제 변경 입고일을 확인해야 합니다."
        ],
    }


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


def test_analysis_not_required_does_not_force_manual_review():
    request = createCobaltRequest().model_copy(
        update={
            "erpAnalysisRequired": False,
        }
    )

    result = analyze_soojung_erp_node(
        {
            "erp_context": request.model_dump(
                mode="json",
            ),
        }
    )

    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 0
    assert (
        assessment["manual_review_required"]
        is False
    )
    assert result["questions_for_contract_agent"] == []
    assert result["affected_contract_ids"] == []


def test_invalid_erp_context_routes_to_manual_review():
    result = analyze_soojung_erp_node(
        {
            "erp_context": {
                "requestId": "ERP-REQ-BAD",
            },
        }
    )

    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 0
    assert (
        assessment["manual_review_required"]
        is True
    )
    assert (
        "ERP_REQUEST_INVALID"
        in assessment["findings"][0]
    )
    assert result["questions_for_contract_agent"] == []
    assert result["affected_contract_ids"] == []


# ==================================================================
# 협상 라운드(recheck_soojung_erp_node) — 이전에는 score_after가 항상
# score_before와 같아서 "재검토"가 이름만 있었다. 이제 contract_gap_score를
# ERP 리스크 공식의 6번째 컴포넌트(contractProtection, 가중치 0.15)로 실제
# 반영해서 재계산하고, 아직 근거가 부족하면 최대 2라운드까지 왕복한다.
# ==================================================================


def test_recheck_converges_when_protective_clause_found():
    """보호조항을 고유사도로 찾으면 1라운드에서 바로 수렴(round2 요청 없음)."""

    state = {
        **_erp_state_after_initial_run(),
        "contract_assessment": {
            "contract_gap_score": 25,
            "protection_status": "protected",
        },
        "contract_findings": [
            {"contract_id": 10, "page": 1},
        ],
        "negotiation_round": 0,
    }

    score_before = state["erp_assessment"][
        "erp_exposure_score"
    ]
    assert score_before == 100

    result = recheck_soojung_erp_node(state)

    # 100(초기값, 5개 컴포넌트 전부 최댓값) * 0.85 + 25(contract_gap_score) * 0.15
    assert (
        result["erp_assessment"]["erp_exposure_score"]
        == 88.75
    )
    assert result["negotiation_round"] == 1
    assert result["questions_for_erp_agent"] == []
    assert result["questions_for_contract_agent_round2"] == []
    assert result["erp_reassessment"]["score_before"] == 100
    assert (
        result["erp_reassessment"]["score_after"]
        == 88.75
    )
    assert any(
        "1차 재검토" in finding
        for finding in result["erp_assessment"]["findings"]
    )


def test_recheck_requests_round2_when_still_uncertain():
    """무관 조항만 찾았고 여전히 위험 등급이면 Contract Agent에 재요청한다."""

    state = {
        **_erp_state_after_initial_run(),
        "contract_assessment": {
            "contract_gap_score": 65,
            "protection_status": "unprotected",
        },
        "contract_findings": [
            {"contract_id": 10, "page": 1},
        ],
        "negotiation_round": 0,
    }

    result = recheck_soojung_erp_node(state)

    # 100*0.85 + 65*0.15 = 94.75 -> critical(>=60) 그대로라 아직 불안
    assert (
        result["erp_assessment"]["erp_exposure_score"]
        == 94.75
    )
    assert result["negotiation_round"] == 1
    assert result["questions_for_contract_agent_round2"] != []


def test_recheck_stops_at_round_cap_even_if_still_uncertain():
    """이미 1라운드를 돈 상태(negotiation_round=1)에서 2번째로 호출되면
    상한(MAX_NEGOTIATION_ROUNDS=2)에 닿아 근거가 부족해도 재요청을 멈춘다."""

    state = {
        **_erp_state_after_initial_run(),
        "contract_assessment": {
            "contract_gap_score": 80,
            "protection_status": "not_found",
        },
        "contract_findings": [],
        "negotiation_round": 1,
    }

    result = recheck_soojung_erp_node(state)

    assert result["negotiation_round"] == 2
    assert result["questions_for_contract_agent_round2"] == []