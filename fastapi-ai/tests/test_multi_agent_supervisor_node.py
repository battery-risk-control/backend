from app.multi_agent.nodes.supervisor_node import (
    select_next_route,
    supervisor_node,
)


KG_PASSED_STATE = {
    "kg_context": {"matched": True},
    "kg_shortage_detected": True,
}


def test_routes_to_kg_first():
    assert select_next_route({}) == "kg"


def test_routes_to_finish_when_kg_gate_fails():
    state = {
        "kg_context": {"matched": False},
        "kg_shortage_detected": False,
    }

    assert select_next_route(state) == "finish"


def test_routes_to_erp_after_kg_gate_passes():
    assert select_next_route(KG_PASSED_STATE) == "erp"


def test_routes_to_contract_after_erp():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
    }

    assert select_next_route(state) == "contract"


def test_routes_to_erp_recheck_after_contract():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "questions_for_erp_agent": [
            "변경된 입고 예정일을 확인해 주세요.",
        ],
        "erp_reassessment_done": False,
    }

    assert select_next_route(state) == "erp_recheck"


def test_routes_to_risk_after_recheck():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "questions_for_erp_agent": [
            "변경된 입고 예정일을 확인해 주세요.",
        ],
        "erp_reassessment_done": True,
    }

    assert select_next_route(state) == "risk"


def test_routes_to_response_after_risk():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "erp_reassessment_done": True,
        "procurement_risk_level": "warning",
    }

    assert select_next_route(state) == "response"


def test_routes_to_reviewer_after_response():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "erp_reassessment_done": True,
        "procurement_risk_level": "warning",
        "briefing": "구매 위험 브리핑입니다.",
    }

    assert select_next_route(state) == "reviewer"


def test_routes_to_failed_contract_agent():
    state = {
        "review_passed": False,
        "error_owner": "contract",
        "retry_count": 1,
    }

    assert select_next_route(state) == "contract"


def test_finishes_after_review_passes():
    state = {
        "review_passed": True,
        "retry_count": 0,
    }

    assert select_next_route(state) == "finish"


def test_finishes_after_maximum_retries():
    state = {
        "review_passed": False,
        "error_owner": "response",
        "retry_count": 2,
    }

    assert select_next_route(state) == "finish"


def test_supervisor_node_records_next_route():
    result = supervisor_node({})

    assert result == {
        "supervisor_next": "kg",
    }
