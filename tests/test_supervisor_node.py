from app.nodes.supervisor_node import select_next_route


def test_routes_to_erp_first():
    state = {}

    assert select_next_route(state) == "erp"


def test_routes_to_contract_after_erp():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 70
        }
    }

    assert select_next_route(state) == "contract"


def test_routes_to_risk_after_contract():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 70
        },
        "contract_assessment": {
            "contract_gap_score": 50
        }
    }

    assert select_next_route(state) == "risk"


def test_routes_to_response_after_risk():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 70
        },
        "contract_assessment": {
            "contract_gap_score": 50
        },
        "procurement_risk_level": "warning"
    }

    assert select_next_route(state) == "response"


def test_routes_to_reviewer_after_briefing():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 70
        },
        "contract_assessment": {
            "contract_gap_score": 50
        },
        "procurement_risk_level": "warning",
        "briefing": "니켈 공급 차질에 대한 확인이 필요합니다."
    }

    assert select_next_route(state) == "reviewer"


def test_routes_to_failed_agent():
    state = {
        "review_passed": False,
        "error_owner": "contract",
        "retry_count": 1
    }

    assert select_next_route(state) == "contract"


def test_finishes_after_review_passes():
    state = {
        "review_passed": True,
        "retry_count": 0
    }

    assert select_next_route(state) == "finish"


def test_finishes_after_maximum_retries():
    state = {
        "review_passed": False,
        "error_owner": "response",
        "retry_count": 2
    }

    assert select_next_route(state) == "finish"

def test_routes_to_erp_recheck_after_contract_question():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 60
        },
        "contract_assessment": {
            "contract_gap_score": 30
        },
        "questions_for_erp_agent": [
            "변경된 납기를 반영해 재고를 확인해야 하는가?"
        ],
        "erp_reassessment_done": False,
    }

    assert select_next_route(state) == "erp_recheck"


def test_routes_to_risk_after_erp_recheck():
    state = {
        "erp_assessment": {
            "erp_exposure_score": 60
        },
        "contract_assessment": {
            "contract_gap_score": 30
        },
        "questions_for_erp_agent": [
            "변경된 납기를 반영해 재고를 확인해야 하는가?"
        ],
        "erp_reassessment_done": True,
    }

    assert select_next_route(state) == "risk"
