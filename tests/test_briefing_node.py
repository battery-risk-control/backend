from app.nodes.briefing_node import generate_briefing_node


def test_briefing_node_combines_erp_and_contract_findings():
    state = {
        "news_id": "news-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "impact_domain_final": "logistics",
        "severity_tier": "warning",
        "affected_materials": ["Nickel"],

        "erp_findings": {
            "inventory_status": "warning",
            "findings": [
                "현재 재고가 안전재고 기준보다 2일 부족합니다.",
                "미입고 발주가 2건 존재합니다."
            ]
        },

        "contract_findings": [
            {
                "contract_id": "MOCK-CONTRACT-001",
                "material": "Nickel",
                "clause_type": "delivery_delay",
                "clause_name_kr": "납기 지연 조항",
                "evidence_text": "공급자는 지연 사유와 변경 일정을 통보해야 한다.",
                "page": 1,
                "source_type": "mock"
            }
        ]
    }

    result = generate_briefing_node(state)
    briefing = result["briefing"]

    assert "[뉴스 ID] news-001" in briefing
    assert "[영향 영역] logistics" in briefing
    assert "[심각도] warning" in briefing
    assert "안전재고 기준보다 2일 부족" in briefing
    assert "납기 지연 조항" in briefing


def test_briefing_node_handles_missing_findings():
    state = {
        "news_id": "news-002",
        "title": "일반 배터리 산업 뉴스",
        "impact_domain_final": "unknown",
        "severity_tier": "normal",
        "affected_materials": []
    }

    result = generate_briefing_node(state)
    briefing = result["briefing"]

    assert "ERP 분석 결과가 없습니다." in briefing
    assert "관련 계약 조항을 찾지 못했습니다." in briefing


def test_briefing_node_uses_final_procurement_risk():
    state = {
        "news_id": "news-003",
        "title": "니켈 공급 중단 위험",
        "impact_domain_final": "production",
        "affected_materials": ["Nickel"],
        "procurement_risk_level": "critical",
        "procurement_risk_score": 85,
        "risk_reasons": [
            "입고 예정일 전에 재고가 소진될 가능성이 있습니다."
        ],
        "erp_assessment": {
            "erp_exposure_score": 90,
            "findings": [
                "등록된 대체 공급사가 없습니다."
            ],
        },
        "contract_findings": [
            {
                "contract_id": "CONTRACT-001",
                "clause_name_kr": "불가항력 조항",
                "evidence_text": "공급 중단 시 즉시 통보해야 한다.",
                "page": 3,
            }
        ],
    }

    result = generate_briefing_node(state)
    briefing = result["briefing"]

    assert "[심각도] critical" in briefing
    assert "[위험 점수] 85" in briefing
    assert "[CONTRACT-001 p.3]" in briefing
    assert "[권고 조치]" in briefing

    assert len(result["recommended_actions"]) == 4

def test_briefing_node_uses_llm_when_enabled(
    monkeypatch,
):
    def fake_generate_response_with_llm(state):
        return {
            "recommended_actions": [
                "공급사에 변경된 납기 일정을 확인합니다."
            ],
            "briefing": "LLM이 생성한 구매팀 브리핑입니다.",
        }

    monkeypatch.setattr(
        "app.nodes.briefing_node."
        "generate_response_with_llm",
        fake_generate_response_with_llm,
    )

    state = {
        "use_llm": True,
        "procurement_risk_level": "warning",
        "procurement_risk_score": 60,
    }

    result = generate_briefing_node(state)

    assert result["llm_used"] is True
    assert result["llm_error"] is None
    assert (
        result["briefing"]
        == "LLM이 생성한 구매팀 브리핑입니다."
    )
    assert len(result["recommended_actions"]) == 1


def test_briefing_node_falls_back_when_llm_fails(
    monkeypatch,
):
    def fake_failed_llm(state):
        raise TimeoutError("테스트용 timeout")

    monkeypatch.setattr(
        "app.nodes.briefing_node."
        "generate_response_with_llm",
        fake_failed_llm,
    )

    state = {
        "news_id": "news-fallback-001",
        "title": "LLM 실패 테스트",
        "use_llm": True,
        "impact_domain_final": "logistics",
        "procurement_risk_level": "warning",
        "procurement_risk_score": 60,
        "affected_materials": ["Nickel"],
    }

    result = generate_briefing_node(state)

    assert result["llm_used"] is False
    assert "TimeoutError" in result["llm_error"]

    # LLM 호출이 실패해도 규칙 기반 결과를 반환
    assert "[뉴스 ID] news-fallback-001" in result["briefing"]
    assert len(result["recommended_actions"]) == 3
