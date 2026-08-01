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
        product_id=None,
        customer_id=None,
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


# 그래프 단위 테스트는 kg_service 실제 호출 없이 erp/contract/risk 파이프라인을
# 검증하는 게 목적이라, KG 게이트를 이미 통과한 상태로 시작한다.
KG_GATE_PASSED = {
    "kg_context": {"matched": True},
    "kg_shortage_detected": True,
}


def test_briefing_graph_runs_all_agents():
    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    graph = build_briefing_graph(
        FakeRagService(),
    )
    erp_request = createCobaltRequest()

    initial_state = {
        **KG_GATE_PASSED,
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
        "erp_context": erp_request.model_dump(
            mode="json",
        ),
    }

    result = graph.invoke(initial_state)

    # FakeRagService가 반환하는 조항은 "납기 지연"/"통보" 키워드로
    # delivery_delay(보호조항)로 분류되고 similarity_score=0.92 >= 0.7이라
    # contract_gap_score=25(protected). 원래 5개 컴포넌트가 전부 100이었던
    # 최초 점수(100)에, erp_recheck 협상 라운드에서 contractProtection(0.15
    # 가중치)로 25점을 실제로 반영해 100*0.85 + 25*0.15 = 88.75로 내려간다 —
    # 이게 바로 "재검토가 진짜로 점수를 바꾼다"는 것의 증거다.
    assert result["erp_assessment"][
        "erp_exposure_score"
    ] == 88.75
    assert len(result["contract_findings"]) == 1
    assert result["negotiation_round"] == 1
    assert result["llm_used"] is False
    assert result["briefing"]
    assert result["review_passed"] is True
    assert result["supervisor_next"] == "finish"


def test_graph_uses_injected_rag_service():
    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    rag_service = FakeRagService()
    graph = build_briefing_graph(rag_service)
    erp_request = createCobaltRequest()

    result = graph.invoke(
        {
            **KG_GATE_PASSED,
            "news_id": "news-graph-002",
            "title": "코발트 공급 지연",
            "impact_domain_final": "logistics",
            "affected_materials": ["Cobalt"],
            "external_signal_score": 20,
            "use_llm": False,
            "retry_count": 0,
            "rag_contract_id": 1001,
            "erp_context": erp_request.model_dump(
                mode="json",
            ),
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
            **KG_GATE_PASSED,
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

    # 같은 FakeRagService라 contract_gap_score=25(protected)가 반영되어
    # 100*0.85 + 25*0.15 = 88.75. forcedCritical은 erpExposureScore와 무관하게
    # 등급을 critical로 강제하므로 exposure_level은 그대로 critical.
    assert assessment["erp_exposure_score"] == 88.75
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


def test_graph_visits_outbound_contract_when_stockout_and_outbound_id_present():
    """재고부족(stockout_before_eta)이 확정되고 outbound_contract_id가 있으면
    그래프가 outbound_contract 노드를 실제로 방문해 배상책임 근거를 채운다."""

    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    graph = build_briefing_graph(
        FakeRagService(),
    )
    erp_request = createCobaltRequest()

    result = graph.invoke(
        {
            **KG_GATE_PASSED,
            "news_id": "news-outbound-001",
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
            "outbound_contracts": [
                {"contract_id": 501, "product_id": 601, "customer_id": 701},
            ],
            "outbound_contracts_total_matched": 1,
            "erp_context": erp_request.model_dump(
                mode="json",
            ),
        },
    )

    assert result["erp_assessment"]["stockout_before_eta"] is True
    assert result["outbound_contract_checked"] is True
    assert len(result["outbound_contract_findings"]) == 1
    assert result["review_passed"] is True


def test_graph_skips_outbound_contract_without_outbound_id():
    """outbound_contract_id가 없으면(리졸브 실패/매칭 없음) 조용히 건너뛴다."""

    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    graph = build_briefing_graph(
        FakeRagService(),
    )
    erp_request = createCobaltRequest()

    result = graph.invoke(
        {
            **KG_GATE_PASSED,
            "news_id": "news-outbound-002",
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

    assert result.get("outbound_contract_findings", []) == []
    assert result["review_passed"] is True

    assert result["erp_exposure_response"][
        "requestId"
    ] == "ERP-REQ-004"

    assert result["procurement_risk_level"] == (
        "critical"
    )
    assert result["review_passed"] is True


@dataclass
class RoundAwareRagResult:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    page_number: int
    content: str
    similarity_score: float


class RoundAwareRagService:
    """1라운드엔 무관 조항만, 2라운드엔 보호조항을 돌려주는 가짜 RAG.

    ERP↔Contract 협상 루프가 실제로 2번 왕복하는지(rag.search가 2번
    호출되는지) 그래프 레벨에서 검증하기 위한 픽스처.
    """

    def __init__(self):
        self.calls = 0

    def search(
        self,
        query,
        contract_id,
        supplier_id,
        top_k,
        material_id=None,
        product_id=None,
        customer_id=None,
    ):
        self.calls += 1

        if self.calls == 1:
            return [
                RoundAwareRagResult(
                    document_id="round1-doc",
                    contract_id=1001,
                    supplier_id=2001,
                    material_id=3001,
                    page_number=1,
                    content=(
                        "비밀유지 조항입니다. "
                        "confidential 정보를 다룹니다."
                    ),
                    similarity_score=0.9,
                ),
            ]

        return [
            RoundAwareRagResult(
                document_id="round2-doc",
                contract_id=1001,
                supplier_id=2001,
                material_id=3001,
                page_number=2,
                content=(
                    "불가항력(force majeure) 조항입니다."
                ),
                similarity_score=0.9,
            ),
        ]


def test_negotiation_loop_actually_revisits_contract_agent():
    """무관 조항만 찾은 1라운드 뒤, ERP가 재확인을 요청해 Contract Agent를
    한 번 더 돌리고(왕복), 2라운드에서 찾은 보호조항이 최종 점수에
    실제로 반영되는지 그래프 전체를 실행해서 확인한다."""

    from tests.test_erp_calculator import (
        createCobaltRequest,
    )

    rag_service = RoundAwareRagService()
    graph = build_briefing_graph(rag_service)
    erp_request = createCobaltRequest()

    result = graph.invoke(
        {
            **KG_GATE_PASSED,
            "news_id": "news-negotiation-round2",
            "title": "콩고 코발트 광산 파업",
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

    # Contract Agent가 실제로 두 번 호출됐다 — 진짜 왕복이 일어났다는 증거.
    assert rag_service.calls == 2
    assert result["negotiation_round"] == 2
    assert (
        result["contract_assessment"]["protection_status"]
        == "protected"
    )
    # 100*0.85 + 25(2라운드에 찾은 protected 조항) * 0.15
    assert (
        result["erp_assessment"]["erp_exposure_score"]
        == 88.75
    )
    assert result["review_passed"] is True