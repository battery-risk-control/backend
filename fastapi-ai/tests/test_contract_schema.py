from datetime import datetime

import pytest
from pydantic import ValidationError

from app.schemas.contract import (
    ContractAgentQuestion,
    ContractAgentRequest,
    ContractErpContext,
)


def createValidContractRequest():
    return ContractAgentRequest(
        requestId="CONTRACT-ERP-REQ-004",
        eventId="EVT-20260722-004",
        erpRequestId="ERP-REQ-004",
        asOf=datetime.fromisoformat(
            "2026-07-22T09:00:00+09:00"
        ),
        affectedMaterialIds=[
            "MAT-CO-SULF",
        ],
        affectedSupplierIds=[
            "SUP-COD-01",
        ],
        contractIds=[
            "CTR-010",
        ],
        questions=[
            ContractAgentQuestion(
                questionId="ERP-REQ-004-Q01",
                questionCode=(
                    "DELIVERY_PENALTY"
                ),
                contractId="CTR-010",
                question=(
                    "납품 지연 시 위약금 "
                    "조항이 있는가?"
                ),
            ),
        ],
        erpContext=ContractErpContext(
            erpExposureScore=100,
            exposureLevel="CRITICAL",
            inventoryDays=6.5,
            expectedSupplyGapDays=8.5,
            projectedSupplyGapDays=8.5,
            selectedPurchaseOrderId=(
                "PO-0004"
            ),
            selectedPurchaseOrderItemId=(
                "POI-0004"
            ),
        ),
    )


def testValidContractRequest():
    request = createValidContractRequest()

    assert (
        request.eventId
        == "EVT-20260722-004"
    )
    assert request.contractIds == [
        "CTR-010"
    ]
    assert (
        request.questions[0].contractId
        == "CTR-010"
    )
    assert (
        request.erpContext.inventoryDays
        == 6.5
    )


def testDuplicateContractIdRejected():
    requestData = (
        createValidContractRequest()
        .model_dump()
    )

    requestData["contractIds"] = [
        "CTR-010",
        "CTR-010",
    ]

    with pytest.raises(
        ValidationError,
        match="DUPLICATE_CONTRACT_ID",
    ):
        ContractAgentRequest(
            **requestData
        )


def testDuplicateQuestionIdRejected():
    requestData = (
        createValidContractRequest()
        .model_dump()
    )

    firstQuestion = (
        requestData["questions"][0]
    )

    requestData["questions"] = [
        firstQuestion,
        firstQuestion,
    ]

    with pytest.raises(
        ValidationError,
        match=(
            "DUPLICATE_CONTRACT_QUESTION_ID"
        ),
    ):
        ContractAgentRequest(
            **requestData
        )


def testUnknownQuestionContractRejected():
    requestData = (
        createValidContractRequest()
        .model_dump()
    )

    requestData["questions"][0][
        "contractId"
    ] = "CTR-999"

    with pytest.raises(
        ValidationError,
        match=(
            "QUESTION_CONTRACT_NOT_REGISTERED"
        ),
    ):
        ContractAgentRequest(
            **requestData
        )


def testTimezoneRequired():
    requestData = (
        createValidContractRequest()
        .model_dump()
    )

    requestData["asOf"] = datetime(
        2026,
        7,
        22,
        9,
        0,
        0,
    )

    with pytest.raises(
        ValidationError,
        match=(
            "CONTRACT_AS_OF_TIMEZONE_MISSING"
        ),
    ):
        ContractAgentRequest(
            **requestData
        )


def testQuestionsCannotBeEmpty():
    requestData = (
        createValidContractRequest()
        .model_dump()
    )

    requestData["questions"] = []

    with pytest.raises(ValidationError):
        ContractAgentRequest(
            **requestData
        )