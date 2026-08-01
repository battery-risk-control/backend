from app.multi_agent.graph.state import BriefingState
from app.multi_agent.rag.service_adapter import (
    RagSearchService,
    search_contract_evidence,
)


OUTBOUND_LIABILITY_QUERY = (
    "납품 지연 배상 책임 지체상금 penalty liability late delivery"
)


def analyze_outbound_contract_node(
    state: BriefingState,
    service: RagSearchService,
) -> dict:
    """재고부족이 완성차 고객사 납품 지연으로 이어질 경우의 배상책임 근거를 찾는다.

    인바운드 Contract Agent(contract_agent.py)와 목적이 달라 별도 파일로 분리한다 —
    인바운드는 "계약이 우리를 얼마나 보호하는가", 이건 "재고부족이 지속되면 우리가
    고객사에 얼마나 물어줘야 하는가"라 섞으면 코드가 헷갈린다.

    Spring이 kg_service /resolve로 이미 리졸브하고 재무 노출도(계약가치×penalty_pct+
    line_stop_charge) 상위 N건으로 추린 outbound_contracts를 그대로 순회하며 계약별로
    검색한다 — FastAPI는 DB 접근이 없어 여기서 직접 리졸브/랭킹할 수 없다
    (app/multi_agent/kg/node.py가 받는 kg_affected_outbound_contract_ids는 외부 문자열
    ID라 내부 PK 변환이 필요한데, 그건 Spring 쪽 책임).
    """

    contracts = state.get("outbound_contracts") or []

    if not contracts:
        return {
            "outbound_contract_findings": [],
            "outbound_contract_checked": True,
        }

    findings: list[dict] = []

    for contract in contracts:
        findings.extend(
            search_contract_evidence(
                service=service,
                query=OUTBOUND_LIABILITY_QUERY,
                contract_id=contract["contract_id"],
                product_id=contract["product_id"],
                customer_id=contract["customer_id"],
                top_k=5,
            )
        )

    return {
        "outbound_contract_findings": findings,
        "outbound_contract_checked": True,
    }
