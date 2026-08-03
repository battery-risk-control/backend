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


def create_valid_state_with_scores():
    state = create_valid_state()
    state["briefing"] = (
        "최종 위험 단계는 심각입니다. "
        "계약 ID 1001의 페이지 1에서 납기 지연 통보 조항을 확인했습니다. "
        "점수 구성: 외부신호 70점 · ERP노출 71.25점 · 계약공백 50점. "
        "재고는 현재 6.5일치이며 안전재고 18.0일 대비 부족합니다. "
        "최종 구매 위험 점수는 65점입니다."
    )
    state["external_signal_score"] = 70
    state["procurement_risk_score"] = 65
    state["erp_assessment"]["erp_exposure_score"] = 71.25
    state["erp_assessment"]["inventory_days"] = 6.5
    state["erp_assessment"]["safety_stock_days"] = 18.0
    state["contract_assessment"] = {"contract_gap_score": 50}
    return state


def test_verification_passes_when_numbers_match_state():
    result = validate_briefing_node(
        create_valid_state_with_scores(),
    )

    assert result["review_passed"] is True
    assert result["warnings"] == []


def test_verification_rejects_hallucinated_score():
    state = create_valid_state_with_scores()
    # ERP노출 점수를 실제 값(71.25)과 다르게 적어 환각을 재현한다.
    state["briefing"] = state["briefing"].replace(
        "ERP노출 71.25점",
        "ERP노출 40점",
    )

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"
    assert any(
        "ERP노출 점수(71.25)" in warning
        for warning in result["warnings"]
    )


def test_verification_rejects_hallucinated_inventory_days():
    state = create_valid_state_with_scores()
    # 재고 일수를 실제 값(6.5)과 다르게 적어 환각을 재현한다.
    state["briefing"] = state["briefing"].replace(
        "6.5일치",
        "9.5일치",
    )

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"
    assert any(
        "재고 일수(6.5)" in warning
        for warning in result["warnings"]
    )


def test_numeric_fact_check_skipped_when_value_absent():
    # 조기종료 경량 브리핑처럼 점수 자체가 없는 상태 — 검사 대상이 없으면
    # 통과해야 하고, 없는 값 때문에 오탐이 나면 안 된다.
    state = create_valid_state()
    state["briefing"] = (
        "최종 위험 단계는 심각입니다. "
        "계약 ID 1001의 페이지 1에서 납기 지연 통보 조항을 확인했습니다."
    )

    result = validate_briefing_node(state)

    assert result["review_passed"] is True