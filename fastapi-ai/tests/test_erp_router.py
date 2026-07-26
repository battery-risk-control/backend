import pytest

from app.agents.erp_exposure_agent import (
    runErpExposureAgent,
)
from app.graph.routers import (
    routeAfterErpAgent,
)
from app.graph.state import (
    createErpAgentState,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


def testContractReviewRoutesToContractAgent():
    request = createCobaltRequest()
    state = createErpAgentState(request)

    agentResult = runErpExposureAgent(state)

    nextNode = routeAfterErpAgent(agentResult)

    assert nextNode == "contractAgent"


def testAnalysisNotRequiredRoutesToFinalRisk():
    request = createCobaltRequest().model_copy(
        update={
            "erpAnalysisRequired": False,
        }
    )

    state = createErpAgentState(request)
    agentResult = runErpExposureAgent(state)

    nextNode = routeAfterErpAgent(agentResult)

    assert nextNode == "finalRiskCalculator"


def testFailedAgentRoutesToManualReview():
    state = {
        "eventId": "EVT-001",
        "newsIds": [],
        "erpAgentStatus": "PENDING",
        "errors": [],
    }

    agentResult = runErpExposureAgent(state)

    nextNode = routeAfterErpAgent(agentResult)

    assert nextNode == "manualReview"


def testPendingAgentCannotBeRouted():
    request = createCobaltRequest()
    state = createErpAgentState(request)

    with pytest.raises(
        ValueError,
        match="ERP_AGENT_NOT_FINISHED",
    ):
        routeAfterErpAgent(state)


def testInconsistentRouteIsRejected():
    state = {
        "eventId": "EVT-001",
        "newsIds": [],
        "erpAgentStatus": "FAILED",
        "erpNextNode": "contractAgent",
        "errors": ["ERP 계산 실패"],
    }

    with pytest.raises(
        ValueError,
        match="ERP_ROUTE_INCONSISTENT",
    ):
        routeAfterErpAgent(state)


def testMissingNextNodeIsRejected():
    state = {
        "eventId": "EVT-001",
        "newsIds": [],
        "erpAgentStatus": "COMPLETED",
        "errors": [],
    }

    with pytest.raises(
        ValueError,
        match="ERP_NEXT_NODE_MISSING",
    ):
        routeAfterErpAgent(state)