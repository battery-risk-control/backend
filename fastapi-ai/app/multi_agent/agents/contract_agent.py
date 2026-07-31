from app.multi_agent.graph.state import BriefingState
from app.multi_agent.rag.service_adapter import (
    RagSearchService,
    search_contract_evidence,
)


def build_contract_query(state: BriefingState) -> str:
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

    if contract_id is None and supplier_id is None:
        return {
            "contract_assessment": {
                "contract_gap_score": 80,
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
                "contract_gap_score": 80,
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

    return {
        "contract_assessment": {
            "contract_gap_score": 30,
            "protection_status": "partial",
            "questions_received": questions_received,
            "search_query": query,
            "requires_human_confirmation": False,
        },
        "contract_findings": contract_findings,
        "questions_for_erp_agent": [
            "검색된 계약 근거를 반영한 실제 변경 입고일을 "
            "확인해야 합니다."
        ],
    }