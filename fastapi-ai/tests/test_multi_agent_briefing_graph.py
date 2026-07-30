from dataclasses import dataclass

from app.multi_agent.graph.briefing_graph import (
    build_briefing_graph,
)


@dataclass
class FakeRagResult:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    page_number: int
    content: str
    similarity_score: float


class FakeRagService:
    def search(
        self,
        query,
        contract_id,
        supplier_id,
        top_k,
        material_id=None,
    ):
        return [
            FakeRagResult(
                document_id="document-001",
                contract_id=1001,
                supplier_id=2001,
                material_id=3001,
                page_number=1,
                content=(
                    "공급자는 납기 지연 사유와 "
                    "변경 일정을 통보해야 한다."
                ),
                similarity_score=0.92,
            ),
        ]


def test_briefing_graph_runs_all_agents():
    graph = build_briefing_graph(
        FakeRagService(),
    )

    initial_state = {
        "news_id": "news-graph-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "summary_kr": (
            "항만 파업으로 니켈 공급 일정에 "
            "차질이 예상됩니다."
        ),
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"],
        "external_signal_level": "warning",
        "external_signal_score": 60,
        "use_llm": False,
        "retry_count": 0,
        "rag_contract_id": 1001,
        "rag_supplier_id": 2001,
        "rag_material_id": 3001,
        "erp_context": {
            "inventory_days": 18,
            "safety_stock_days": 20,
            "open_orders": 2,
            "supplier_dependency": 0.80,
            "next_inbound_eta_days": 25,
            "contract_adjusted_eta_days": 25,
            "has_alternative_supplier": False,
            "contract_ids": ["CTR-001"],
        },
    }

    result = graph.invoke(initial_state)

    assert result["erp_assessment"][
        "erp_exposure_score"
    ] == 100
    assert len(result["contract_findings"]) == 1
    assert result["erp_reassessment_done"] is True
    assert result["procurement_risk_score"] == 72
    assert (
        result["procurement_risk_level"]
        == "critical"
    )
    assert result["llm_used"] is False
    assert result["briefing"]
    assert result["review_passed"] is True
    assert result["supervisor_next"] == "finish"


def test_graph_uses_injected_rag_service():
    rag_service = FakeRagService()
    graph = build_briefing_graph(rag_service)

    result = graph.invoke(
        {
            "news_id": "news-graph-002",
            "title": "코발트 공급 지연",
            "impact_domain_final": "logistics",
            "affected_materials": ["Cobalt"],
            "external_signal_score": 20,
            "use_llm": False,
            "retry_count": 0,
            "rag_contract_id": 1001,
            "erp_context": {
                "inventory_days": 30,
                "safety_stock_days": 20,
                "open_orders": 1,
                "supplier_dependency": 0.30,
                "next_inbound_eta_days": 10,
                "has_alternative_supplier": True,
                "contract_ids": ["CTR-001"],
            },
        },
    )

    assert (
        result["contract_findings"][0][
            "source_type"
        ]
        == "chroma"
    )
    assert (
        result["contract_findings"][0][
            "document_id"
        ]
        == "document-001"
    )

def test_graph_uses_soojung_erp_agent():
    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    graph = build_briefing_graph(
        FakeRagService(),
    )
    erp_request = createCobaltRequest()

    result = graph.invoke(
        {
            "news_id": "news-soojung-001",
            "title": "코발트 선적 지연",
            "summary_kr": "코발트 공급 차질이 예상됩니다.",
            "impact_domain_final": "logistics",
            "affected_materials": ["Cobalt"],
            "external_signal_level": "critical",
            "external_signal_score": 82,
            "use_llm": False,
            "retry_count": 0,
            "rag_contract_id": 1001,
            "rag_supplier_id": 2001,
            "rag_material_id": 3001,
            "erp_context": erp_request.model_dump(
                mode="json",
            ),
        },
    )

    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 100
    assert assessment["exposure_level"] == "critical"
    assert assessment["inventory_days"] == 6.5
    assert (
        assessment["expected_supply_gap_days"]
        == 8.5
    )
    assert assessment["stockout_before_eta"] is True

    assert result["affected_contract_ids"] == [
        "CTR-010",
    ]
    assert len(
        result["questions_for_contract_agent"]
    ) == 5

    assert result["erp_exposure_response"][
        "requestId"
    ] == "ERP-REQ-004"

    assert result["procurement_risk_level"] == (
        "critical"
    )
    assert result["review_passed"] is True