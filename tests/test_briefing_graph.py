from app.graph.briefing_graph import briefing_graph


def test_briefing_graph_runs_all_nodes():
    initial_state = {
        "news_id": "news-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "article_text": "항만 파업으로 니켈 선적이 지연되고 있다.",

        "impact_domain_draft": "logistics",
        "impact_domain_final": "logistics",

        "severity_tier": "warning",
        "severity_score": 60,

        "affected_materials": ["Nickel"],

        # Spring Boot가 ERP에서 조회해서 전달한다고 가정한다.
        "erp_context": {
            "inventory_days": 18,
            "safety_stock_days": 20,
            "open_orders": 2,
            "supplier_dependency": 0.72
        },

        "retry_count": 0,
        "warnings": []
    }

    result = briefing_graph.invoke(initial_state)

    # ERP 에이전트 결과
    assert result["erp_findings"]["inventory_status"] == "warning"
    assert len(result["erp_findings"]["findings"]) > 0

    # =========================================================
    # Contract 결과를 반영한 ERP Agent 재실행 결과
    # =========================================================

    assert result["erp_reassessment_done"] is True

    assert len(
        result["erp_reassessment"]["checked_questions"]
    ) == 1

    # 테스트 ERP 데이터에는 계약 조정 입고일이 없으므로
    # 담당자 확인이 필요한 상태가 된다.
    assert (
        result["erp_reassessment"][
            "requires_human_confirmation"
        ]
        is True
    )

    assert any(
        "변경된 입고 일정이 ERP 데이터에 없어"
        in finding
        for finding in result[
            "erp_reassessment"
        ]["findings"]
    )

    # RAG 에이전트 결과
    assert len(result["contract_findings"]) == 1
    assert (
        result["contract_findings"][0]["clause_type"]
        == "delivery_delay"
    )

    # 브리핑 통합 노드 결과
    assert "[뉴스 ID] news-001" in result["briefing"]
    assert "[영향 영역] logistics" in result["briefing"]
    assert "납기 지연 조항" in result["briefing"]

    # 검증 노드 결과
    assert result["validation_passed"] is True
    assert result["warnings"] == []
