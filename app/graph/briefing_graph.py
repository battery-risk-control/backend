from langgraph.graph import END, START, StateGraph

from app.agents.erp_agent import (
    analyze_erp_node,
    recheck_erp_node,
)
from app.agents.rag_agent import search_contracts_node
from app.graph.state import BriefingState
from app.nodes.briefing_node import generate_briefing_node
from app.nodes.risk_node import calculate_procurement_risk_node
from app.nodes.supervisor_node import supervisor_node
from app.nodes.verification_node import validate_briefing_node


def run_erp_agent(state: BriefingState) -> dict:
    """
    ERP Agent를 실행한다.

    Reviewer 요청으로 ERP가 재실행되면 계약 분석부터 브리핑까지
    다시 계산할 수 있도록 이후 단계의 결과를 초기화한다.
    """
    result = analyze_erp_node(state)

    return {
        **result,
        "contract_assessment": {},
        "erp_reassessment": {},
        "erp_reassessment_done": False,
        "contract_findings": [],
        "procurement_risk_level": None,
        "procurement_risk_score": 0,
        "briefing": "",
        "recommended_actions": [],
        "review_passed": None,
        "error_owner": None,

    }


def run_contract_agent(state: BriefingState) -> dict:
    """
    Contract RAG Agent를 실행한다.

    계약 분석이 변경되면 위험도와 브리핑을 다시 계산한다.
    """
    result = search_contracts_node(state)

    return {
        **result,
        "erp_reassessment": {},
        "erp_reassessment_done": False,
        "procurement_risk_level": None,
        "procurement_risk_score": 0,
        "briefing": "",
        "recommended_actions": [],
        "review_passed": None,
        "error_owner": None,
    }

def run_erp_recheck(
    state: BriefingState,
) -> dict:
    """
    Contract Agent의 질문을 반영해 같은 ERP Agent를 재호출한다.

    계약 분석 결과는 유지하고 이후 위험도와 브리핑만
    다시 계산하도록 초기화한다.
    """
    result = recheck_erp_node(state)

    return {
        **result,
        "procurement_risk_level": None,
        "procurement_risk_score": 0,
        "briefing": "",
        "recommended_actions": [],
        "review_passed": None,
        "error_owner": None,
    }

def run_response_agent(state: BriefingState) -> dict:
    """
    Response Agent를 실행한다.

    브리핑을 다시 생성한 뒤 Reviewer가 재검사하도록
    이전 검증 결과를 초기화한다.
    """
    result = generate_briefing_node(state)

    return {
        **result,
        "review_passed": None,
        "error_owner": None,
    }


def route_from_supervisor(
    state: BriefingState,
) -> str:
    """Supervisor가 State에 기록한 다음 경로를 반환한다."""
    return state["supervisor_next"]


def build_briefing_graph():
    graph_builder = StateGraph(BriefingState)

    # =========================================================
    # 1. 노드 등록
    # =========================================================

    graph_builder.add_node(
        "supervisor",
        supervisor_node,
    )
    graph_builder.add_node(
        "erp",
        run_erp_agent,
    )
    graph_builder.add_node(
        "contract",
        run_contract_agent,
    )
    graph_builder.add_node(
        "erp_recheck",
        run_erp_recheck,
    )
    graph_builder.add_node(
        "risk",
        calculate_procurement_risk_node,
    )
    graph_builder.add_node(
        "response",
        run_response_agent,
    )
    graph_builder.add_node(
        "reviewer",
        validate_briefing_node,
    )

    # =========================================================
    # 2. Supervisor로 시작
    # =========================================================

    graph_builder.add_edge(
        START,
        "supervisor",
    )

    # =========================================================
    # 3. Supervisor의 판단에 따른 조건부 실행
    # =========================================================

    graph_builder.add_conditional_edges(
        "supervisor",
        route_from_supervisor,
        {
            "erp": "erp",
            "contract": "contract",
            "erp_recheck": "erp_recheck",
            "risk": "risk",
            "response": "response",
            "reviewer": "reviewer",
            "finish": END,
        },
    )

    # =========================================================
    # 4. 각 작업이 끝나면 Supervisor에게 복귀
    # =========================================================

    graph_builder.add_edge(
        "erp",
        "supervisor",
    )
    graph_builder.add_edge(
        "contract",
        "supervisor",
    )
    graph_builder.add_edge(
        "erp_recheck",
        "supervisor",
    )
    graph_builder.add_edge(
        "risk",
        "supervisor",
    )
    graph_builder.add_edge(
        "response",
        "supervisor",
    )
    graph_builder.add_edge(
        "reviewer",
        "supervisor",
    )


    return graph_builder.compile()


briefing_graph = build_briefing_graph()
