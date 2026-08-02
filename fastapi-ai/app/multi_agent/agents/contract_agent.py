from app.multi_agent.graph.state import BriefingState
from app.multi_agent.rag.service_adapter import (
    RagSearchService,
    search_contract_evidence,
)
from app.services.contract_rule_loader import (
    loadContractRules,
)


def compute_contract_gap(
    findings: list[dict],
) -> tuple[int, str]:
    """검색된 계약 조항들로 contract_gap_score와 protection_status를 계산한다.

    ID가 없어서 검색 자체를 못 했거나 결과가 없는 경우는 호출부에서 별도 처리하고,
    이 함수는 findings가 실제로 있는 경우만 다룬다.

    보호조항(protectiveClauseTypes) 후보가 하나라도 있으면 그중 유사도가 가장 높은
    것을 기준으로 판정한다 — 전체 findings 중 최고 유사도 항목만 보면, 우연히
    유사도가 더 높은 무관 조항(예: 결제조항)이 실제 보호조항을 가려버릴 수 있어서
    (예: payment 0.99 vs delivery_delay 0.71 — 후자가 진짜 근거인데 전자에
    가려짐), 보호조항 후보를 먼저 걸러낸 뒤 그 안에서만 최고 유사도를 고른다.
    """

    rules = loadContractRules()
    gap_risk = rules["contractGapRisk"]
    protective_types = set(rules["protectiveClauseTypes"])

    protective_findings = [
        finding
        for finding in findings
        if finding["clause_type"] in protective_types
    ]

    if not protective_findings:
        return gap_risk["nonProtective"]["score"], "unprotected"

    best_protective = max(
        protective_findings,
        key=lambda f: f["similarity_score"],
    )

    min_similarity = gap_risk["protectiveHighSimilarity"][
        "minSimilarity"
    ]
    if best_protective["similarity_score"] >= min_similarity:
        return gap_risk["protectiveHighSimilarity"]["score"], "protected"

    return gap_risk["protectiveLowSimilarity"]["score"], "partial"


def build_contract_query(state: BriefingState) -> str:
    # 2라운드 재검토 질문(ERP가 협상 루프 중 다시 요청한 것)이 있으면
    # 최우선으로 반영한다 — 1라운드 질문은 이미 한 번 검색에 반영됐으므로 낡은 정보다.
    round2_questions = state.get(
        "questions_for_contract_agent_round2",
        [],
    )

    if round2_questions:
        return " ".join(round2_questions)

    questions = state.get(
        "questions_for_contract_agent",
        [],
    )

    if questions:
        return " ".join(questions)

    title = state.get("title", "")
    impact_domain = state.get(
        "impact_domain_final",
        "unknown",
    )
    query = f"{title} {impact_domain}".strip()

    # KG가 이미 원산지 공급사/재고부족을 확정한 경우, 그 근거 문장을
    # 검색어에 실어 RAG 의미검색이 같은 사건을 가리키도록 돕는다.
    # (KG는 외부 문자열 ID 기준이라 rag_contract_id처럼 정확한 필터로는
    # 못 쓰지만, 검색어 보강에는 그대로 활용할 수 있다.)
    kg_evidence_paths = state.get(
        "kg_evidence_paths",
        [],
    )
    if kg_evidence_paths:
        query = f"{query} {' '.join(kg_evidence_paths)}".strip()

    return query

def analyze_contracts_node(
    state: BriefingState,
    service: RagSearchService,
) -> dict:
    questions_received = state.get(
        "questions_for_contract_agent",
        [],
    )

    contract_id = state.get("rag_contract_id")
    supplier_id = state.get("rag_supplier_id")
    material_id = state.get("rag_material_id")
    rules = loadContractRules()

    if contract_id is None and supplier_id is None:
        return {
            "contract_assessment": {
                "contract_gap_score": rules["contractGapRisk"][
                    "notSearchable"
                ]["score"],
                "protection_status": "not_searched",
                "questions_received": questions_received,
                "requires_human_confirmation": True,
            },
            "contract_findings": [],
            "questions_for_erp_agent": [
                "계약 검색에 필요한 내부 계약 ID 또는 "
                "공급사 ID를 확인해야 합니다."
            ],
        }

    query = build_contract_query(state)

    contract_findings = search_contract_evidence(
        service=service,
        query=query,
        contract_id=contract_id,
        supplier_id=supplier_id,
        material_id=material_id,
        top_k=5,
    )

    if not contract_findings:
        return {
            "contract_assessment": {
                "contract_gap_score": rules["contractGapRisk"][
                    "notFound"
                ]["score"],
                "protection_status": "not_found",
                "questions_received": questions_received,
                "search_query": query,
                "requires_human_confirmation": True,
            },
            "contract_findings": [],
            "questions_for_erp_agent": [
                "검색 결과에서 관련 계약 조항을 확인하지 "
                "못했습니다. 현재 재고와 대체 조달 계획을 "
                "확인해야 합니다."
            ],
        }

    contract_gap_score, protection_status = compute_contract_gap(
        contract_findings
    )

    return {
        "contract_assessment": {
            "contract_gap_score": contract_gap_score,
            "protection_status": protection_status,
            "questions_received": questions_received,
            "search_query": query,
            "requires_human_confirmation": protection_status
            != "protected",
        },
        "contract_findings": contract_findings,
        "questions_for_erp_agent": [
            "검색된 계약 근거를 반영한 실제 변경 입고일을 "
            "확인해야 합니다."
        ],
    }