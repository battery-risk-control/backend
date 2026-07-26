from app.graph.erp_workflow import (
    createErpWorkflow,
)
from app.graph.state import (
    createErpAgentState,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


def testCriticalExposureRunsContractRoute():
    workflow = createErpWorkflow()

    request = createCobaltRequest()

    initialState = createErpAgentState(
        request=request,
        newsIds=[
            "NEWS-001",
            "NEWS-002",
        ],
    )

    result = workflow.invoke(initialState)

    assert (
        result["erpAgentStatus"]
        == "COMPLETED"
    )
    assert (
        result["erpNextNode"]
        == "contractAgent"
    )
    assert result["erpExposure"] is not None
    assert (
        result["erpExposure"]
        .contractReviewRequired
        is True
    )

    assert result["contractRequest"] is not None

    assert (
        result["contractRequest"].contractIds
        == ["CTR-010"]
    )

def testAnalysisNotRequiredRunsFinalRiskRoute():
    workflow = createErpWorkflow()

    request = createCobaltRequest().model_copy(
        update={
            "erpAnalysisRequired": False,
        }
    )

    initialState = createErpAgentState(
        request=request,
    )

    result = workflow.invoke(initialState)

    assert (
        result["erpAgentStatus"]
        == "NOT_REQUIRED"
    )
    assert (
        result["erpNextNode"]
        == "finalRiskCalculator"
    )
    assert result["erpExposure"] is None

def testMissingRequestRunsManualReviewRoute():
    workflow = createErpWorkflow()

    initialState = {
        "eventId": "EVT-MISSING-ERP",
        "newsIds": ["NEWS-003"],
        "erpAgentStatus": "PENDING",
        "errors": [],
    }

    result = workflow.invoke(initialState)

    assert (
        result["erpAgentStatus"]
        == "FAILED"
    )
    assert (
        result["erpNextNode"]
        == "manualReview"
    )
    assert (
        "ERP_REQUEST_MISSING"
        in result["errors"][0]
    )

def testEventIdMismatchRunsManualReviewRoute():
    workflow = createErpWorkflow()

    request = createCobaltRequest()

    initialState = createErpAgentState(
        request=request,
    )

    initialState["eventId"] = (
        "DIFFERENT-EVENT"
    )

    result = workflow.invoke(initialState)

    assert (
        result["erpAgentStatus"]
        == "FAILED"
    )
    assert (
        result["erpNextNode"]
        == "manualReview"
    )
    assert (
        "ERP_EVENT_ID_MISMATCH"
        in result["errors"][0]
    )