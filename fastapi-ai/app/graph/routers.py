from app.graph.state import (
    ErpNextNode,
    ProcurementRiskState,
)


ALLOWED_ROUTES_BY_STATUS: dict[
    str,
    set[str],
] = {
    "COMPLETED": {
        "contractAgent",
        "finalRiskCalculator",
        "manualReview",
    },
    "NOT_REQUIRED": {
        "finalRiskCalculator",
    },
    "MANUAL_REVIEW": {
        "manualReview",
    },
    "FAILED": {
        "manualReview",
    },
}


def routeAfterErpAgent(
    state: ProcurementRiskState,
) -> ErpNextNode:
    """
    ERP Exposure Agent 실행 결과를 바탕으로
    다음 LangGraph 노드 이름을 반환한다.
    """

    agentStatus = state.get("erpAgentStatus")
    nextNode = state.get("erpNextNode")

    if agentStatus is None:
        raise ValueError(
            "ERP_AGENT_STATUS_MISSING: "
            "erpAgentStatus가 없습니다."
        )

    if agentStatus == "PENDING":
        raise ValueError(
            "ERP_AGENT_NOT_FINISHED: "
            "ERP Agent가 아직 실행되지 않았습니다."
        )

    if nextNode is None:
        raise ValueError(
            "ERP_NEXT_NODE_MISSING: "
            "erpNextNode가 없습니다."
        )

    allowedRoutes = ALLOWED_ROUTES_BY_STATUS.get(
        agentStatus
    )

    if allowedRoutes is None:
        raise ValueError(
            "ERP_AGENT_STATUS_INVALID: "
            f"지원하지 않는 상태입니다: {agentStatus}"
        )

    if nextNode not in allowedRoutes:
        raise ValueError(
            "ERP_ROUTE_INCONSISTENT: "
            f"status={agentStatus}, "
            f"nextNode={nextNode}"
        )

    return nextNode