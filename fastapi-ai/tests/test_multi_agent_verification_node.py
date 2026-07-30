from app.multi_agent.nodes.verification_node import (
    validate_briefing_node,
)


def create_valid_state():
    return {
        "procurement_risk_level": "critical",
        "briefing": (
            "최종 위험 단계는 심각입니다. "
            "계약 ID 1001의 페이지 1에서 "
            "납기 지연 통보 조항을 확인했습니다."
        ),
        "erp_assessment": {
            "findings": [
                "입고 전에 재고가 소진될 수 있습니다.",
            ],
        },
        "contract_findings": [
            {
                "contract_id": 1001,
                "page": 1,
                "evidence_text": (
                    "공급자는 지연 사유와 변경 "
                    "일정을 통보해야 한다."
                ),
            },
        ],
        "recommended_actions": [
            "기존 발주의 납기와 수량을 확인합니다.",
        ],
        "llm_used": True,
        "retry_count": 0,
    }


def test_verification_passes_with_required_evidence():
    result = validate_briefing_node(
        create_valid_state(),
    )

    assert result["review_passed"] is True
    assert result["validation_passed"] is True
    assert result["error_owner"] is None
    assert result["retry_count"] == 0
    assert result["warnings"] == []


def test_verification_assigns_erp_error_owner():
    state = create_valid_state()
    state["erp_assessment"] = {
        "findings": [],
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "erp"
    assert result["retry_count"] == 1
    assert (
        "주의·심각 사건이지만 ERP 분석 근거가 없습니다."
        in result["warnings"]
    )


def test_verification_assigns_contract_error_owner():
    state = create_valid_state()
    state["contract_findings"] = []

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "contract"
    assert result["retry_count"] == 1


def test_verification_rejects_missing_contract_metadata():
    state = create_valid_state()
    state["contract_findings"] = [
        {
            "contract_id": "",
            "evidence_text": "납기 지연 통보 조항",
        },
    ]

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "contract"
    assert "계약 근거에 contract_id가 없습니다." in (
        result["warnings"]
    )
    assert "계약 근거에 페이지 정보가 없습니다." in (
        result["warnings"]
    )


def test_verification_rejects_missing_llm_page_citation():
    state = create_valid_state()
    state["briefing"] = (
        "최종 위험 단계는 심각입니다. "
        "계약 ID 1001에서 납기 지연 조항을 확인했습니다."
    )

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"
    assert (
        "LLM 브리핑에 계약서 페이지 인용이 없습니다: 1"
        in result["warnings"]
    )


def test_verification_rejects_forbidden_expression():
    state = create_valid_state()
    state["briefing"] += " 공급은 반드시 발생한다."

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"
    assert result["retry_count"] == 1
    assert any(
        "반드시 발생한다" in warning
        for warning in result["warnings"]
    )


def test_rule_based_briefing_does_not_require_llm_citation():
    state = create_valid_state()
    state["llm_used"] = False
    state["briefing"] = "규칙 기반 브리핑입니다."

    result = validate_briefing_node(state)

    assert result["review_passed"] is True