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

    return f"{title} {impact_domain}".strip()

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