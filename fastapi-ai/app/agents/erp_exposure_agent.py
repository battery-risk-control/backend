from app.adapters.contract_agent_adapter import (
    adaptToContractAgentRequest,
)
from app.graph.state import (
    ProcurementRiskState,
)
from app.schemas.erp import (
    ErpExposureResponse,
)
from app.services.erp_exposure_service import (
    calculateErpExposure,
)


def createErpAgentFailure(
    message: str,
    erpExposure: (
        ErpExposureResponse | None
    ) = None,
) -> ProcurementRiskState:
    """
    ERP Agent가 처리를 완료하지 못했을 때
    수동 검토 경로로 전달할 상태를 만든다.

    ERP 계산 이후 Contract 요청 생성에서
    실패한 경우에는 계산 결과를 보존한다.
    """

    return {
        "erpExposure": erpExposure,
        "contractRequest": None,
        "erpAgentStatus": "FAILED",
        "erpNextNode": "manualReview",
        "errors": [message],
    }


def runErpExposureAgent(
    state: ProcurementRiskState,
) -> ProcurementRiskState:
    """
    ERP Exposure Agent Node.

    1. State에서 ERP 요청을 가져온다.
    2. eventId를 검증한다.
    3. ERP Exposure를 계산한다.
    4. 계약 검토가 필요하면
       ContractAgentRequest를 생성한다.
    5. 다음 노드를 결정한다.
    """

    request = state.get("erpRequest")

    if request is None:
        return createErpAgentFailure(
            "ERP_REQUEST_MISSING: "
            "State에 erpRequest가 없습니다."
        )

    stateEventId = state.get("eventId")

    if (
        stateEventId is not None
        and stateEventId != request.eventId
    ):
        return createErpAgentFailure(
            "ERP_EVENT_ID_MISMATCH: "
            f"State eventId={stateEventId}, "
            f"ERP request eventId={request.eventId}"
        )

    if not request.erpAnalysisRequired:
        return {
            "erpExposure": None,
            "contractRequest": None,
            "erpAgentStatus": "NOT_REQUIRED",
            "erpNextNode": (
                "finalRiskCalculator"
            ),
        }

    try:
        result = calculateErpExposure(
            request
        )

    except Exception as error:
        return createErpAgentFailure(
            "ERP_CALCULATION_FAILED: "
            f"{type(error).__name__}: {error}"
        )

    if result.manualReviewRequired:
        return {
            "erpExposure": result,
            "contractRequest": None,
            "erpAgentStatus": "COMPLETED",
            "erpNextNode": "manualReview",
        }

    if result.contractReviewRequired:
        try:
            contractRequest = (
                adaptToContractAgentRequest(
                    erpRequest=request,
                    erpResponse=result,
                )
            )

        except Exception as error:
            return createErpAgentFailure(
                message=(
                    "CONTRACT_REQUEST_BUILD_FAILED: "
                    f"{type(error).__name__}: "
                    f"{error}"
                ),
                erpExposure=result,
            )

        return {
            "erpExposure": result,
            "contractRequest": (
                contractRequest
            ),
            "erpAgentStatus": "COMPLETED",
            "erpNextNode": "contractAgent",
        }

    return {
        "erpExposure": result,
        "contractRequest": None,
        "erpAgentStatus": "COMPLETED",
        "erpNextNode": (
            "finalRiskCalculator"
        ),
    }