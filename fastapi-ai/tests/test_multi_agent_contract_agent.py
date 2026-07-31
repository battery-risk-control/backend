from dataclasses import dataclass

from app.multi_agent.agents.contract_agent import (
    analyze_contracts_node,
    build_contract_query,
)


@dataclass
class FakeSearchResult:
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
        query: str,
        contract_id: int | None,
        supplier_id: int | None,
        top_k: int,
        material_id: int | None = None,
    ) -> list[FakeSearchResult]:
        return [
            FakeSearchResult(
                document_id="DOC-001",
                contract_id=10,
                supplier_id=20,
                material_id=30,
                page_number=3,
                content=(
                    "공급자는 납기 지연 발생 시 "
                    "변경 일정을 통보해야 한다."
                ),
                similarity_score=0.91,
            )
        ]


def test_contract_agent_returns_real_rag_evidence():
    state = {
        "title": "항만 파업으로 니켈 선적 지연",
        "impact_domain_final": "logistics",
        "questions_for_contract_agent": [
            "납기 지연 시 적용할 수 있는 계약 조항이 있는가?"
        ],
        "rag_contract_id": 10,
        "rag_supplier_id": 20,
        "rag_material_id": 30,
    }

    result = analyze_contracts_node(
        state,
        service=FakeRagService(),
    )

    assert (
        result["contract_assessment"]["contract_gap_score"]
        == 30
    )
    assert (
        result["contract_assessment"]["protection_status"]
        == "partial"
    )
    assert len(result["contract_findings"]) == 1
    assert result["contract_findings"][0]["contract_id"] == 10
    assert result["contract_findings"][0]["page"] == 3
    assert (
        "납기 지연"
        in result["contract_findings"][0]["evidence_text"]
    )
    assert len(result["questions_for_erp_agent"]) == 1

def test_build_contract_query_appends_kg_evidence_when_no_questions():
    state = {
        "title": "항만 파업으로 니켈 선적 지연",
        "impact_domain_final": "logistics",
        "kg_evidence_paths": [
            "인도네시아 니켈 공급사 재고 부족 확인",
        ],
    }

    query = build_contract_query(state)

    assert "항만 파업으로 니켈 선적 지연" in query
    assert "인도네시아 니켈 공급사 재고 부족 확인" in query


def test_build_contract_query_prefers_explicit_questions_over_kg():
    state = {
        "title": "항만 파업으로 니켈 선적 지연",
        "questions_for_contract_agent": [
            "납기 지연 시 적용할 수 있는 계약 조항이 있는가?",
        ],
        "kg_evidence_paths": [
            "인도네시아 니켈 공급사 재고 부족 확인",
        ],
    }

    query = build_contract_query(state)

    assert query == "납기 지연 시 적용할 수 있는 계약 조항이 있는가?"


def test_contract_agent_requires_internal_search_id():
    state = {
        "title": "항만 파업으로 니켈 선적 지연",
        "impact_domain_final": "logistics",
        "questions_for_contract_agent": [
            "납기 지연 조항을 확인해야 합니다."
        ],
    }

    result = analyze_contracts_node(
        state,
        service=FakeRagService(),
    )

    assessment = result["contract_assessment"]

    assert assessment["contract_gap_score"] == 80
    assert assessment["protection_status"] == "not_searched"
    assert assessment["requires_human_confirmation"] is True
    assert result["contract_findings"] == []
    assert (
        "내부 계약 ID 또는 공급사 ID"
        in result["questions_for_erp_agent"][0]
    )