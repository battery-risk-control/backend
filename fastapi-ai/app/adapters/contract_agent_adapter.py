from app.schemas.contract import (
    ContractAgentQuestion,
    ContractAgentRequest,
    ContractErpContext,
)
from app.schemas.erp import (
    ErpExposureRequest,
    ErpExposureResponse,
)


def validateContractAgentSource(
    erpRequest: ErpExposureRequest,
    erpResponse: ErpExposureResponse,
) -> None:
    """
    Contract Agent 요청을 만들 수 있는
    ERP 입력과 결과인지 검증한다.
    """

    if (
        erpRequest.requestId
        != erpResponse.requestId
    ):
        raise ValueError(
            "ERP_REQUEST_ID_MISMATCH: "
            f"request={erpRequest.requestId}, "
            f"response={erpResponse.requestId}"
        )

    if erpResponse.manualReviewRequired:
        raise ValueError(
            "ERP_MANUAL_REVIEW_REQUIRED"
        )

    if not erpResponse.contractReviewRequired:
        raise ValueError(
            "CONTRACT_REVIEW_NOT_REQUIRED"
        )

    if not erpResponse.affectedContractIds:
        raise ValueError(
            "AFFECTED_CONTRACT_IDS_MISSING"
        )

    if not erpResponse.questionsForContractAgent:
        raise ValueError(
            "CONTRACT_QUESTIONS_MISSING"
        )

    registeredContractIds = set(
        erpResponse.affectedContractIds
    )

    for question in (
        erpResponse.questionsForContractAgent
    ):
        if question.contractId is None:
            raise ValueError(
                "QUESTION_CONTRACT_ID_MISSING: "
                f"{question.questionCode.value}"
            )

        if (
            question.contractId
            not in registeredContractIds
        ):
            raise ValueError(
                "QUESTION_CONTRACT_NOT_AFFECTED: "
                f"{question.contractId}"
            )


def adaptToContractAgentRequest(
    erpRequest: ErpExposureRequest,
    erpResponse: ErpExposureResponse,
) -> ContractAgentRequest:
    """
    ERP Agent의 요청과 분석 결과를
    Contract Agent 입력 DTO로 변환한다.
    """

    validateContractAgentSource(
        erpRequest=erpRequest,
        erpResponse=erpResponse,
    )

    questions: list[
        ContractAgentQuestion
    ] = []

    for index, sourceQuestion in enumerate(
        erpResponse.questionsForContractAgent,
        start=1,
    ):
        # validateContractAgentSource에서
        # None 여부를 먼저 검사했다.
        contractId = sourceQuestion.contractId

        if contractId is None:
            raise ValueError(
                "QUESTION_CONTRACT_ID_MISSING"
            )

        questions.append(
            ContractAgentQuestion(
                questionId=(
                    f"{erpRequest.requestId}"
                    f"-Q{index:02d}"
                ),
                questionCode=(
                    sourceQuestion.questionCode
                ),
                contractId=contractId,
                question=sourceQuestion.question,
            )
        )

    return ContractAgentRequest(
        requestId=(
            f"CONTRACT-{erpRequest.requestId}"
        ),
        eventId=erpRequest.eventId,
        erpRequestId=erpRequest.requestId,
        asOf=erpRequest.asOf,
        affectedMaterialIds=list(
            erpResponse.affectedMaterialIds
        ),
        affectedSupplierIds=list(
            erpResponse.affectedSupplierIds
        ),
        contractIds=list(
            erpResponse.affectedContractIds
        ),
        questions=questions,
        erpContext=ContractErpContext(
            erpExposureScore=(
                erpResponse.erpExposureScore
            ),
            exposureLevel=(
                erpResponse.exposureLevel
            ),
            inventoryDays=(
                erpResponse.facts.inventoryDays
            ),
            expectedSupplyGapDays=(
                erpResponse.facts
                .expectedSupplyGapDays
            ),
            projectedSupplyGapDays=(
                erpResponse.facts
                .projectedSupplyGapDays
            ),
            selectedPurchaseOrderId=(
                erpResponse.facts
                .selectedPurchaseOrderId
            ),
            selectedPurchaseOrderItemId=(
                erpResponse.facts
                .selectedPurchaseOrderItemId
            ),
        ),
    )