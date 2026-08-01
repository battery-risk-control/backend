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
        "negotiation_round": 0,
    }

    assert select_next_route(state) == "erp_recheck"


def test_routes_to_risk_after_recheck():
    # erp_recheck가 처리를 마치면 questions_for_erp_agent를 비우고
    # negotiation_round를 1로 올린다(수렴 시 questions_for_contract_agent_round2도
    # 비워둠) — 그래서 다음 supervisor 호출에선 더 이상 erp_recheck로 안 돌아간다.
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "questions_for_erp_agent": [],
        "questions_for_contract_agent_round2": [],
        "negotiation_round": 1,
    }

    assert select_next_route(state) == "risk"


def test_routes_to_contract_for_negotiation_round2():
    # ERP 재검토가 아직 근거가 부족하다고 판단해 Contract Agent에게
    # 한 번 더 확인을 요청한 경우 — 라운드 한도 안이면 진짜로 왕복한다.
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 65,
        },
        "questions_for_erp_agent": [],
        "questions_for_contract_agent_round2": [
            "불가항력 조항이 있는지 다시 확인해야 합니다.",
        ],
        "negotiation_round": 1,
    }

    assert select_next_route(state) == "contract"


def test_negotiation_stops_at_round_cap():
    # 라운드 상한(MAX_NEGOTIATION_ROUNDS=2)에 닿으면 round2 질문이 남아있어도
    # 더 왕복하지 않고 risk로 넘어간다 — 무한루프 방지.
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
        },
        "contract_assessment": {
            "contract_gap_score": 65,
        },
        "questions_for_erp_agent": [],
        "questions_for_contract_agent_round2": [
            "불가항력 조항이 있는지 다시 확인해야 합니다.",
        ],
        "negotiation_round": 2,
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
        "procurement_risk_level": "warning",
    }

    assert select_next_route(state) == "response"


def test_routes_to_outbound_contract_when_stockout_and_id_present():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
            "stockout_before_eta": True,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "procurement_risk_level": "warning",
        "outbound_contract_id": 501,
        "outbound_contract_checked": False,
    }

    assert select_next_route(state) == "outbound_contract"


def test_skips_outbound_contract_without_outbound_id():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
            "stockout_before_eta": True,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "procurement_risk_level": "warning",
        "outbound_contract_id": None,
    }

    assert select_next_route(state) == "response"


def test_skips_outbound_contract_without_stockout():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
            "stockout_before_eta": False,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "procurement_risk_level": "warning",
        "outbound_contract_id": 501,
    }

    assert select_next_route(state) == "response"


def test_skips_outbound_contract_when_already_checked():
    state = {
        **KG_PASSED_STATE,
        "erp_assessment": {
            "erp_exposure_score": 70,
            "stockout_before_eta": True,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "procurement_risk_level": "warning",
        "outbound_contract_id": 501,
        "outbound_contract_checked": True,
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
