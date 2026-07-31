from dataclasses import dataclass

from app.multi_agent.agents.contract_agent import (
    analyze_contracts_node,
    build_contract_query,
    compute_contract_gap,
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
        product_id: int | None = None,
        customer_id: int | None = None,
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

    # 본문에 "지연"/"납기" 키워드가 있어 delivery_delay(보호조항)로 분류되고
    # similarity_score=0.91 >= 0.7(protectiveHighSimilarity 임계값)이라
    # contract_gap_score=25/protection_status="protected"가 나온다.
    assert (
        result["contract_assessment"]["contract_gap_score"]
        == 25
    )
    assert (
        result["contract_assessment"]["protection_status"]
        == "protected"
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


# ==================================================================
# compute_contract_gap — contract_rules.yaml의 5개 구간이 clause_type ×
# similarity_score 조합별로 정확히 나오는지 직접 검증한다. 이전에는
# 하드코딩 80/80/30 세 값뿐이었다.
# ==================================================================


def test_compute_contract_gap_protective_high_similarity():
    findings = [
        {
            "clause_type": "force_majeure",
            "similarity_score": 0.85,
        },
    ]

    score, status = compute_contract_gap(findings)

    assert score == 25
    assert status == "protected"


def test_compute_contract_gap_protective_low_similarity():
    findings = [
        {
            "clause_type": "volume_commitment",
            "similarity_score": 0.5,
        },
    ]

    score, status = compute_contract_gap(findings)

    assert score == 50
    assert status == "partial"


def test_compute_contract_gap_non_protective_clause():
    findings = [
        {
            "clause_type": "confidentiality",
            "similarity_score": 0.95,
        },
    ]

    score, status = compute_contract_gap(findings)

    assert score == 65
    assert status == "unprotected"


def test_compute_contract_gap_prefers_protective_clause_over_higher_similarity_noise():
    findings = [
        {
            "clause_type": "payment",
            "similarity_score": 0.99,
        },
        {
            "clause_type": "delivery_delay",
            "similarity_score": 0.71,
        },
    ]

    # 전체 findings 중 최고 유사도는 payment(0.99)지만 무관 조항이다.
    # 보호조항(delivery_delay, 0.71)이 있으면 그쪽을 우선해야 한다 — 우연히
    # 유사도가 더 높은 무관 조항이 실제 보호 근거를 가리면 안 된다.
    score, status = compute_contract_gap(findings)

    assert score == 25
    assert status == "protected"


def test_compute_contract_gap_falls_back_to_non_protective_when_no_protective_candidate():
    findings = [
        {
            "clause_type": "payment",
            "similarity_score": 0.99,
        },
        {
            "clause_type": "confidentiality",
            "similarity_score": 0.80,
        },
    ]

    score, status = compute_contract_gap(findings)

    assert score == 65
    assert status == "unprotected"