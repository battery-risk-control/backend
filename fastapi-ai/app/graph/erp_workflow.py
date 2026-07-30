from langgraph.graph import (
    END,
    START,
    StateGraph,
)

from app.agents.erp_exposure_agent import (
    runErpExposureAgent,
)
from app.graph.routers import (
    routeAfterErpAgent,
)
from app.graph.state import (
    ProcurementRiskState,
)


def contractAgentPlaceholder(
    state: ProcurementRiskState,
) -> ProcurementRiskState:
    """
    Contract Intelligence Agent 임시 노드.

    현재는 그래프 연결만 검증한다.
    실제 Contract Agent 구현 후 교체한다.
    """

    return {}


def finalRiskCalculatorPlaceholder(
    state: ProcurementRiskState,
) -> ProcurementRiskState:
    """
    최종 구매 리스크 계산 임시 노드.

    현재는 그래프 연결만 검증한다.
    """

    return {}


def manualReviewPlaceholder(
    state: ProcurementRiskState,
) -> ProcurementRiskState:
    """
    수동 검토 임시 노드.

    ERP 계산 실패 또는 데이터 품질 문제가 있을 때 도착한다.
    """

    return {}


def createErpWorkflow():
    """
    ERP Exposure Agent를 중심으로 한
    LangGraph 서브그래프를 생성한다.
    """

    workflow = StateGraph(
        ProcurementRiskState
    )

    workflow.add_node(
        "erpExposureAgent",
        runErpExposureAgent,
    )

    workflow.add_node(
        "contractAgent",
        contractAgentPlaceholder,
    )

    workflow.add_node(
        "finalRiskCalculator",
        finalRiskCalculatorPlaceholder,
    )

    workflow.add_node(
        "manualReview",
        manualReviewPlaceholder,
    )

    workflow.add_edge(
        START,
        "erpExposureAgent",
    )

    workflow.add_conditional_edges(
        "erpExposureAgent",
        routeAfterErpAgent,
        {
            "contractAgent": "contractAgent",
            "finalRiskCalculator": (
                "finalRiskCalculator"
            ),
            "manualReview": "manualReview",
        },
    )

    workflow.add_edge(
        "contractAgent",
        END,
    )

    workflow.add_edge(
        "finalRiskCalculator",
        END,
    )

    workflow.add_edge(
        "manualReview",
        END,
    )

    return workflow.compile()