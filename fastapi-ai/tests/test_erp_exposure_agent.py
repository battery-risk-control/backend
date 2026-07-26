from app.agents.erp_exposure_agent import (
    runErpExposureAgent,
)
from app.graph.state import (
    createErpAgentState,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


# contractRequest가 생성됐는지 검사
def testCriticalExposureRoutesToContractAgent():
    request = createCobaltRequest()

    state = createErpAgentState(
        request=request,
        newsIds=["NEWS-001", "NEWS-002"],
    )

    result = runErpExposureAgent(state)

    assert result["erpAgentStatus"] == "COMPLETED"
    assert result["erpNextNode"] == "contractAgent"

    assert result["erpExposure"] is not None
    assert (
        result["erpExposure"].contractReviewRequired
        is True
    )
    assert (
        result["erpExposure"].erpExposureScore
        == 100
    )

    assert result["contractRequest"] is not None

    contractRequest = result["contractRequest"]

    assert (
        contractRequest.eventId
        == request.eventId
    )
    assert contractRequest.contractIds == ["CTR-010"]
    assert len(contractRequest.questions) == 5

    # 입력 State를 직접 변경하지 않았는지 확인
    assert state["erpAgentStatus"] == "PENDING"
    assert state["erpExposure"] is None
    assert state["contractRequest"] is None

# 계약 검토가 필요 없으므로 생성되지 않았는지 검사
def testAnalysisNotRequiredRoutesToFinalRisk():
    request = createCobaltRequest().model_copy(
        update={
            "erpAnalysisRequired": False,
        }
    )

    state = createErpAgentState(
        request=request,
    )

    result = runErpExposureAgent(state)

    assert result["erpAgentStatus"] == "NOT_REQUIRED"
    assert (
        result["erpNextNode"]
        == "finalRiskCalculator"
    )
    assert result["erpExposure"] is None
    assert result["contractRequest"] is None

# ERP 요청 자체가 없으므로 생성되지 않았는지 검사
def testMissingRequestRoutesToManualReview():
    state = {
        "eventId": "EVT-001",
        "newsIds": [],
        "erpAgentStatus": "PENDING",
        "errors": [],
    }

    result = runErpExposureAgent(state)

    assert result["erpAgentStatus"] == "FAILED"
    assert result["erpNextNode"] == "manualReview"
    assert result["erpExposure"] is None
    assert result["contractRequest"] is None

    assert (
        "ERP_REQUEST_MISSING"
        in result["errors"][0]
    )

def testMismatchedEventIdRoutesToManualReview():
    request = createCobaltRequest()

    state = createErpAgentState(
        request=request,
    )

    state["eventId"] = "DIFFERENT-EVENT"

    result = runErpExposureAgent(state)

    assert result["erpAgentStatus"] == "FAILED"
    assert result["erpNextNode"] == "manualReview"

    assert (
        "ERP_EVENT_ID_MISMATCH"
        in result["errors"][0]
    )

# Adapter 실패 테스트
def testContractRequestBuildFailure(
    monkeypatch,
):
    request = createCobaltRequest()

    state = createErpAgentState(
        request=request,
    )

    def raiseAdapterError(
        erpRequest,
        erpResponse,
    ):
        raise ValueError(
            "테스트용 Adapter 오류"
        )

    monkeypatch.setattr(
        "app.agents.erp_exposure_agent."
        "adaptToContractAgentRequest",
        raiseAdapterError,
    )

    result = runErpExposureAgent(state)

    assert result["erpAgentStatus"] == "FAILED"
    assert (
        result["erpNextNode"]
        == "manualReview"
    )

    # ERP 계산은 성공했으므로 결과는 보존한다.
    assert result["erpExposure"] is not None
    assert (
        result["erpExposure"]
        .erpExposureScore
        == 100
    )

    assert result["contractRequest"] is None

    assert (
        "CONTRACT_REQUEST_BUILD_FAILED"
        in result["errors"][0]
    )