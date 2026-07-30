from app.multi_agent.nodes.risk_node import (
    calculate_procurement_risk_node,
    clamp_score,
    normalize_score,
    score_to_level,
)


def test_calculates_normal_risk():
    state = {
        "external_signal_score": 20,
        "erp_assessment": {
            "erp_exposure_score": 20,
        },
        "contract_assessment": {
            "contract_gap_score": 20,
        },
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 20
    assert result["procurement_risk_level"] == "normal"


def test_calculates_warning_risk():
    state = {
        "external_signal_score": 50,
        "erp_assessment": {
            "erp_exposure_score": 50,
        },
        "contract_assessment": {
            "contract_gap_score": 50,
        },
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 50
    assert result["procurement_risk_level"] == "warning"


def test_calculates_critical_risk():
    state = {
        "external_signal_score": 80,
        "erp_assessment": {
            "erp_exposure_score": 80,
        },
        "contract_assessment": {
            "contract_gap_score": 80,
        },
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 80
    assert result["procurement_risk_level"] == "critical"


def test_calculates_weighted_procurement_risk():
    state = {
        "external_signal_score": 60,
        "erp_assessment": {
            "erp_exposure_score": 100,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 72
    assert result["procurement_risk_level"] == "critical"
    assert result["risk_reasons"] == [
        "외부 공급망 신호 점수: 60",
        "ERP 내부 노출도 점수: 100",
        "계약 보호 공백 점수: 30",
    ]


def test_stockout_before_eta_forces_critical():
    state = {
        "external_signal_score": 10,
        "erp_assessment": {
            "erp_exposure_score": 10,
            "stockout_before_eta": True,
        },
        "contract_assessment": {
            "contract_gap_score": 10,
        },
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 70
    assert result["procurement_risk_level"] == "critical"
    assert (
        "예상 입고일 전에 현재 재고가 "
        "소진될 가능성이 있습니다."
        in result["risk_reasons"]
    )


def test_missing_scores_use_zero():
    result = calculate_procurement_risk_node({})

    assert result["procurement_risk_score"] == 0
    assert result["procurement_risk_level"] == "normal"


def test_score_helpers_restrict_invalid_values():
    assert normalize_score(None) == 0
    assert normalize_score("잘못된 값") == 0
    assert clamp_score(-10) == 0
    assert clamp_score(120) == 100
    assert score_to_level(39) == "normal"
    assert score_to_level(40) == "warning"
    assert score_to_level(70) == "critical"