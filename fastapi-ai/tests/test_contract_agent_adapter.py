import pytest

from app.adapters.contract_agent_adapter import (
    adaptToContractAgentRequest,
)
from app.services.erp_exposure_service import (
    calculateErpExposure,
)
from tests.test_erp_calculator import (
    createCobaltRequest,
)


def createErpRequestAndResponse():
    erpRequest = createCobaltRequest()

    erpResponse = calculateErpExposure(
        erpRequest
    )

    return erpRequest, erpResponse


def testAdaptToContractAgentRequest():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    contractRequest = (
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=erpResponse,
        )
    )

    assert (
        contractRequest.requestId
        == "CONTRACT-ERP-REQ-004"
    )
    assert (
        contractRequest.eventId
        == erpRequest.eventId
    )
    assert (
        contractRequest.erpRequestId
        == erpRequest.requestId
    )
    assert contractRequest.contractIds == [
        "CTR-010"
    ]

    assert len(contractRequest.questions) == 5

    assert (
        contractRequest.questions[0].questionId
        == "ERP-REQ-004-Q01"
    )
    assert (
        contractRequest.questions[-1].questionId
        == "ERP-REQ-004-Q05"
    )

    assert all(
        question.contractId == "CTR-010"
        for question
        in contractRequest.questions
    )

    assert (
        contractRequest.erpContext
        .erpExposureScore
        == 100
    )
    assert (
        contractRequest.erpContext
        .inventoryDays
        == 6.5
    )
    assert (
        contractRequest.erpContext
        .expectedSupplyGapDays
        == 8.5
    )


def testMismatchedRequestIdRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    mismatchedResponse = (
        erpResponse.model_copy(
            update={
                "requestId": "ERP-OTHER",
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="ERP_REQUEST_ID_MISMATCH",
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=mismatchedResponse,
        )


def testContractReviewNotRequiredRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    noReviewResponse = (
        erpResponse.model_copy(
            update={
                "contractReviewRequired": False,
                "questionsForContractAgent": [],
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="CONTRACT_REVIEW_NOT_REQUIRED",
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=noReviewResponse,
        )


def testManualReviewResultRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    manualReviewResponse = (
        erpResponse.model_copy(
            update={
                "manualReviewRequired": True,
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="ERP_MANUAL_REVIEW_REQUIRED",
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=manualReviewResponse,
        )


def testMissingAffectedContractIdsRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    invalidResponse = (
        erpResponse.model_copy(
            update={
                "affectedContractIds": [],
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="AFFECTED_CONTRACT_IDS_MISSING",
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=invalidResponse,
        )


def testQuestionWithoutContractIdRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    firstQuestion = (
        erpResponse
        .questionsForContractAgent[0]
        .model_copy(
            update={
                "contractId": None,
            }
        )
    )

    invalidResponse = (
        erpResponse.model_copy(
            update={
                "questionsForContractAgent": [
                    firstQuestion
                ],
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="QUESTION_CONTRACT_ID_MISSING",
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=invalidResponse,
        )


def testQuestionForUnaffectedContractRejected():
    erpRequest, erpResponse = (
        createErpRequestAndResponse()
    )

    firstQuestion = (
        erpResponse
        .questionsForContractAgent[0]
        .model_copy(
            update={
                "contractId": "CTR-999",
            }
        )
    )

    invalidResponse = (
        erpResponse.model_copy(
            update={
                "questionsForContractAgent": [
                    firstQuestion
                ],
            }
        )
    )

    with pytest.raises(
        ValueError,
        match=(
            "QUESTION_CONTRACT_NOT_AFFECTED"
        ),
    ):
        adaptToContractAgentRequest(
            erpRequest=erpRequest,
            erpResponse=invalidResponse,
        )