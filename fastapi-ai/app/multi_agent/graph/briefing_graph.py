from collections.abc import Callable

from langgraph.graph import END, START, StateGraph

from app.multi_agent.agents.contract_agent import (
    analyze_contracts_node,
)
from app.multi_agent.agents.erp_agent import (
    analyze_erp_node,
    recheck_erp_node,
)
from app.multi_agent.graph.state import BriefingState
from app.multi_agent.nodes.briefing_node import (
    generate_briefing_node,
)
from app.multi_agent.nodes.risk_node import (
    calculate_procurement_risk_node,
)
from app.multi_agent.nodes.supervisor_node import (
    supervisor_node,
)
from app.multi_agent.nodes.verification_node import (
    validate_briefing_node,
)
from app.multi_agent.rag.service_adapter import (
    RagSearchService,
)


def run_erp_agent(
    state: BriefingState,
) -> dict:
    """ERP Agent를 실행하고 이후 단계의 기존 결과를 초기화한다."""

    result = analyze_erp_node(state)

    return {
        **result,
        "contract_assessment": {},
        "contract_findings": [],
        "questions_for_erp_agent": [],
        "erp_reassessment": {},
        "erp_reassessment_done": False,
        "procurement_risk_level": None,
        "procurement_risk_score": 0,
        "risk_reasons": [],
        "briefing": "",
        "recommended_actions": [],
        "review_passed": None,
        "error_owner": None,
    }


def create_contract_agent_node(
    rag_service: RagSearchService,
) -> Callable[[BriefingState], dict]:
    """
    실제 Minji RagService가 주입된 Contract Agent 노드를 만든다.
    """

    def run_contract_agent(
        state: BriefingState,
    ) -> dict:
        result = analyze_contracts_node(
            state,
            rag_service,
        )

        return {
            **result,
            "erp_reassessment": {},
            "erp_reassessment_done": False,
            "procurement_risk_level": None,
            "procurement_risk_score": 0,
            "risk_reasons": [],
            "briefing": "",
            "recommended_actions": [],
            "review_passed": None,
            "error_owner": None,
        }

    return run_contract_agent


def run_erp_recheck(
    state: BriefingState,
) -> dict:
    """Contract Agent의 질문을 반영해 ERP를 재검토한다."""

    result = recheck_erp_node(state)

    return {
        **result,
        "procurement_risk_level": None,
        "procurement_risk_score": 0,
        "risk_reasons": [],
        "briefing": "",
        "recommended_actions": [],
        "review_passed": None,
        "error_owner": None,
    }


def run_response_agent(
    state: BriefingState,
) -> dict:
    """브리핑을 생성하고 이전 Reviewer 결과를 초기화한다."""

    result = generate_briefing_node(state)

    return {
        **result,
        "review_passed": None,
        "error_owner": None,
    }


def route_from_supervisor(
    state: BriefingState,
) -> str:
    """Supervisor가 상태에 기록한 다음 경로를 반환한다."""

    return state["supervisor_next"]


def build_briefing_graph(
    rag_service: RagSearchService,
):
    """RAG 서비스가 주입된 멀티에이전트 그래프를 생성한다."""

    graph_builder = StateGraph(
        BriefingState,
    )

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
        create_contract_agent_node(
            rag_service,
        ),
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

    graph_builder.add_edge(
        START,
        "supervisor",
    )

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