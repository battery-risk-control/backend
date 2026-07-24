from app.nodes.risk_node import calculate_procurement_risk_node


def test_calculates_normal_risk():
    state = {
        "external_signal_score": 20,
        "erp_assessment": {
            "erp_exposure_score": 40
        },
        "contract_assessment": {
            "contract_gap_score": 25
        }
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 30
    assert result["procurement_risk_level"] == "normal"


def test_calculates_warning_risk():
    state = {
        "external_signal_score": 60,
        "erp_assessment": {
            "erp_exposure_score": 60
        },
        "contract_assessment": {
            "contract_gap_score": 50
        }
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 58
    assert result["procurement_risk_level"] == "warning"


def test_calculates_critical_risk():
    state = {
        "external_signal_score": 90,
        "erp_assessment": {
            "erp_exposure_score": 80
        },
        "contract_assessment": {
            "contract_gap_score": 70
        }
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 82
    assert result["procurement_risk_level"] == "critical"


def test_stockout_before_eta_forces_critical():
    state = {
        "external_signal_score": 10,
        "erp_assessment": {
            "erp_exposure_score": 10,
            "stockout_before_eta": True
        },
        "contract_assessment": {
            "contract_gap_score": 10
        }
    }

    result = calculate_procurement_risk_node(state)

    assert result["procurement_risk_score"] == 70
    assert result["procurement_risk_level"] == "critical"
    assert len(result["risk_reasons"]) == 4


def test_missing_scores_use_zero():
    result = calculate_procurement_risk_node({})

    assert result["procurement_risk_score"] == 0
    assert result["procurement_risk_level"] == "normal"
