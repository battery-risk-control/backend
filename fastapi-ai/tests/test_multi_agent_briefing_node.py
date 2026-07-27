from app.multi_agent.nodes.briefing_node import (
    build_contract_evidence_text,
    generate_briefing_node,
)


def create_state():
    return {
        "news_id": "news-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"],
        "procurement_risk_level": "critical",
        "procurement_risk_score": 72,
        "risk_reasons": [
            "ERP 내부 노출도 점수: 100",
        ],
        "erp_assessment": {
            "findings": [
                (
                    "현재 재고가 안전재고 기준보다 "
                    "2일 부족합니다."
                ),
            ],
        },
        "contract_findings": [
            {
                "contract_id": 1001,
                "page": 1,
                "clause_name_kr": "납기 지연 조항",
                "evidence_text": (
                    "공급자는 지연 사유와 변경 "
                    "일정을 통보해야 한다."
                ),
            },
        ],
    }


def test_generates_rule_based_briefing_without_llm():
    state = {
        **create_state(),
        "use_llm": False,
    }

    result = generate_briefing_node(state)

    assert result["llm_used"] is False
    assert result["llm_error"] is None
    assert "[위험 단계] critical" in result["briefing"]
    assert "[위험 점수] 72" in result["briefing"]
    assert "계약 ID: 1001" in result["briefing"]
    assert "페이지: 1" in result["briefing"]
    assert len(result["recommended_actions"]) == 4


def test_uses_llm_result_when_generation_succeeds(
    monkeypatch,
):
    state = {
        **create_state(),
        "use_llm": True,
    }

    monkeypatch.setattr(
        "app.multi_agent.nodes.briefing_node."
        "generate_response_with_llm",
        lambda _: {
            "recommended_actions": [
                "기존 발주 납기를 확인합니다.",
            ],
            "briefing": "LLM이 생성한 브리핑입니다.",
        },
    )

    result = generate_briefing_node(state)

    assert result["llm_used"] is True
    assert result["llm_error"] is None
    assert (
        result["briefing"]
        == "LLM이 생성한 브리핑입니다."
    )


def test_falls_back_when_llm_generation_fails(
    monkeypatch,
):
    state = {
        **create_state(),
        "use_llm": True,
    }

    def raise_llm_error(_):
        raise TimeoutError("API timeout")

    monkeypatch.setattr(
        "app.multi_agent.nodes.briefing_node."
        "generate_response_with_llm",
        raise_llm_error,
    )

    result = generate_briefing_node(state)

    assert result["llm_used"] is False
    assert result["llm_error"] == (
        "TimeoutError: "
        "LLM 브리핑 생성에 실패했습니다."
    )
    assert "[뉴스 ID] news-001" in result["briefing"]


def test_contract_evidence_does_not_claim_absence():
    result = build_contract_evidence_text([])

    assert "확인하지 못했습니다" in result
    assert "추가 확인이 필요합니다" in result
    assert "조항이 없습니다" not in result


def test_contract_evidence_accepts_rag_adapter_fields():
    findings = [
        {
            "contract_id": 2001,
            "page_number": 3,
            "content": "납기 변경 시 서면 통보한다.",
        },
    ]

    result = build_contract_evidence_text(findings)

    assert "계약 ID: 2001" in result
    assert "페이지: 3" in result
    assert "납기 변경 시 서면 통보한다." in result