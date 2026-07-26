from datetime import datetime

from app.graph.state import createErpAgentState
from app.schemas.erp import (
    ErpExposureRequest,
    ExternalSignalLevel,
    ImpactDomain,
)


def createIrrelevantRequest() -> ErpExposureRequest:
    return ErpExposureRequest(
        requestId="ERP-REQ-STATE-001",
        eventId="EVT-STATE-001",
        asOf=datetime.fromisoformat(
            "2026-07-22T09:00:00+09:00"
        ),
        impactDomain=ImpactDomain.OTHER_IRRELEVANT,
        externalSignalScore=5,
        externalSignalLevel=ExternalSignalLevel.NORMAL,
        eventSummary="구매 및 공급망과 무관한 뉴스입니다.",
        erpAnalysisRequired=False,
    )


def testCreateErpAgentState():
    request = createIrrelevantRequest()

    state = createErpAgentState(
        request=request,
        newsIds=["NEWS-001", "NEWS-002"],
    )

    assert state["eventId"] == "EVT-STATE-001"
    assert state["newsIds"] == [
        "NEWS-001",
        "NEWS-002",
    ]
    assert state["erpRequest"] is request
    assert state["erpExposure"] is None
    assert state["erpAgentStatus"] == "PENDING"
    assert state["errors"] == []
    assert state["contractRequest"] is None


def testCreateErpAgentStateWithoutNewsIds():
    request = createIrrelevantRequest()

    state = createErpAgentState(request=request)

    assert state["newsIds"] == []


def testCreateErpAgentStateCopiesNewsIds():
    request = createIrrelevantRequest()
    newsIds = ["NEWS-001"]

    state = createErpAgentState(
        request=request,
        newsIds=newsIds,
    )

    newsIds.append("NEWS-002")

    assert state["newsIds"] == ["NEWS-001"]