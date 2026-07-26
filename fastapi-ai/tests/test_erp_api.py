from fastapi.testclient import TestClient

from app.main import app

from tests.test_erp_calculator import (
    createCobaltRequest,
)


client = TestClient(app)


def testErpExposureApi() -> None:
    """코발트 ERP 분석 API 정상 응답 테스트."""

    request = createCobaltRequest()

    requestBody = request.model_dump(
        mode="json",
    )

    response = client.post(
        "/api/v1/internal/erp/exposure",
        json=requestBody,
    )

    assert response.status_code == 200

    responseBody = response.json()

    assert (
        responseBody["requestId"]
        == "ERP-REQ-004"
    )

    assert (
        responseBody["facts"][
            "availableQuantity"
        ]
        == 6500
    )

    assert (
        responseBody["facts"][
            "inventoryDays"
        ]
        == 6.5
    )

    assert (
        responseBody["facts"][
            "expectedSupplyGapDays"
        ]
        == 8.5
    )

    assert (
        responseBody["erpExposureScore"]
        == 100
    )

    assert (
        responseBody["exposureLevel"]
        == "CRITICAL"
    )

    assert (
        responseBody["forcedCritical"]
        is True
    )

    assert (
        responseBody[
            "contractReviewRequired"
        ]
        is True
    )

    assert (
        responseBody[
            "manualReviewRequired"
        ]
        is False
    )

    assert (
        responseBody[
            "dataQualityStatus"
        ]
        == "VALID"
    )

    assert len(
        responseBody[
            "questionsForContractAgent"
        ]
    ) >= 1

    assert len(
        responseBody[
            "calculationEvidence"
        ]
    ) >= 1

def testOtherIrrelevantCannotCallErpApi() -> None:
    """
    기타무관 사건은 ERP 분석 API를
    실행할 수 없어야 한다.
    """

    requestBody = {
        "requestId": "ERP-REQ-999",
        "eventId": "EVT-999",
        "asOf": "2026-07-22T09:00:00+09:00",
        "impactDomain": "OTHER_IRRELEVANT",
        "externalSignalScore": 5,
        "externalSignalLevel": "NORMAL",
        "affectedMaterialId": None,
        "affectedSupplierId": None,
        "affectedCountryCode": None,
        "primaryContractId": None,
        "eventSummary": "공급망과 무관한 기사",
        "erpAnalysisRequired": False,
        "materialContext": None,
        "purchaseOrders": [],
    }

    response = client.post(
        "/api/v1/internal/erp/exposure",
        json=requestBody,
    )

    assert response.status_code == 422

    responseBody = response.json()

    assert responseBody["success"] is False

    assert (
        responseBody["error"]["code"]
        == "UNPROCESSABLE_ENTITY"
    )

    assert responseBody["error"]["message"]

def testInvalidErpRequestField() -> None:
    """정의되지 않은 필드가 들어오면 422를 반환."""

    request = createCobaltRequest()

    requestBody = request.model_dump(
        mode="json",
    )

    requestBody["unknownField"] = "invalid"

    response = client.post(
        "/api/v1/internal/erp/exposure",
        json=requestBody,
    )

    assert response.status_code == 422

def testMaterialIdMismatchApi() -> None:
    """영향 자재와 ERP Context 자재가 다르면 422."""

    request = createCobaltRequest()

    requestBody = request.model_dump(
        mode="json",
    )

    requestBody[
        "materialContext"
    ][
        "materialId"
    ] = "MAT-NI-SULF"

    response = client.post(
        "/api/v1/internal/erp/exposure",
        json=requestBody,
    )

    assert response.status_code == 422

    responseBody = response.json()

    assert responseBody["success"] is False

    assert (
        responseBody["error"]["code"]
        == "VALIDATION_ERROR"
    )

    assert responseBody["error"]["details"]