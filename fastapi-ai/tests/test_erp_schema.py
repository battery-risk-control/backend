from pydantic import ValidationError
import pytest

from app.schemas.erp import (
    ErpExposureRequest,
    ExposureLevel,
    ImpactDomain,
    SupplierQualificationStatus,
)


def createCobaltRequestData() -> dict:
    """정상적인 코발트 ERP 분석 요청 데이터."""

    return {
        "requestId": "ERP-REQ-004",
        "eventId": "EVT-20260722-004",
        "asOf": "2026-07-22T09:00:00+09:00",
        "impactDomain": "LOGISTICS",
        "externalSignalScore": 82,
        "externalSignalLevel": "CRITICAL",
        "affectedMaterialId": "MAT-CO-SULF",
        "affectedSupplierId": "SUP-COD-01",
        "affectedCountryCode": "CD",
        "primaryContractId": "CTR-010",
        "eventSummary": (
            "코발트 공급사의 선적 지연이 발생했습니다."
        ),
        "erpAnalysisRequired": True,
        "materialContext": {
            "materialId": "MAT-CO-SULF",
            "materialName": "Cobalt Sulfate",
            "unit": "KG",
            "onHandQuantity": 7280,
            "reservedQuantity": 429,
            "blockedQuantity": 195,
            "qualityHoldQuantity": 156,
            "averageDailyUsage": 1000,
            "safetyStockQuantity": 18000,
            "supplierDependencyRatio": 0.84,
            "alternativeSupplierStatus": "NONE",
            "supplierStatus": "ACTIVE",
            "primarySupplierId": "SUP-COD-01",
            "primaryContractId": "CTR-010",
            "inventorySnapshotAt": (
                "2026-07-22T08:00:00+09:00"
            ),
        },
        "purchaseOrders": [
            {
                "purchaseOrderItemId": "POI-0004",
                "purchaseOrderId": "PO-0004",
                "materialId": "MAT-CO-SULF",
                "supplierId": "SUP-COD-01",
                "contractId": "CTR-010",
                "remainingQuantity": 15000,
                "orderStatus": "DELAYED",
                "effectiveArrivalDate": "2026-08-06",
                "eligibleForEta": True,
            }
        ],
    }

def testValidCobaltRequest() -> None:
    """정상적인 ERP 분석 요청이 생성되는지 확인."""

    requestData = createCobaltRequestData()

    request = ErpExposureRequest.model_validate(
        requestData
    )

    assert request.requestId == "ERP-REQ-004"
    assert request.impactDomain is ImpactDomain.LOGISTICS
    assert request.affectedMaterialId == "MAT-CO-SULF"
    assert request.materialContext is not None

    assert (
        request.materialContext.onHandQuantity
        == 7280
    )

    assert len(request.purchaseOrders) == 1

def testOtherIrrelevantRequest() -> None:
    """
    기타무관 사건은 ERP Context 없이도
    요청 모델을 생성할 수 있어야 한다.
    """

    requestData = {
        "requestId": "ERP-REQ-999",
        "eventId": "EVT-20260722-999",
        "asOf": "2026-07-22T09:00:00+09:00",
        "impactDomain": "OTHER_IRRELEVANT",
        "externalSignalScore": 5,
        "externalSignalLevel": "NORMAL",
        "affectedMaterialId": None,
        "affectedSupplierId": None,
        "affectedCountryCode": None,
        "primaryContractId": None,
        "eventSummary": (
            "배터리 원자재 공급망과 무관한 기사입니다."
        ),
        "erpAnalysisRequired": False,
        "materialContext": None,
        "purchaseOrders": [],
    }

    request = ErpExposureRequest.model_validate(
        requestData
    )

    assert (
        request.impactDomain
        is ImpactDomain.OTHER_IRRELEVANT
    )

    assert request.erpAnalysisRequired is False
    assert request.materialContext is None
    assert request.purchaseOrders == []

def testOtherIrrelevantCannotRunErpAnalysis() -> None:
    """
    기타무관인데 ERP 분석을 요청하면
    ValidationError가 발생해야 한다.
    """

    requestData = {
        "requestId": "ERP-REQ-999",
        "eventId": "EVT-20260722-999",
        "asOf": "2026-07-22T09:00:00+09:00",
        "impactDomain": "OTHER_IRRELEVANT",
        "externalSignalScore": 5,
        "externalSignalLevel": "NORMAL",
        "affectedMaterialId": None,
        "affectedSupplierId": None,
        "affectedCountryCode": None,
        "primaryContractId": None,
        "eventSummary": "공급망과 무관한 기사입니다.",
        "erpAnalysisRequired": True,
        "materialContext": None,
        "purchaseOrders": [],
    }

    with pytest.raises(
        ValidationError,
        match="erpAnalysisRequired=false",
    ):
        ErpExposureRequest.model_validate(requestData)

def testMaterialIdMismatch() -> None:
    """
    영향 자재 ID와 ERP Context 자재 ID가 다르면
    ValidationError가 발생해야 한다.
    """

    requestData = createCobaltRequestData()

    requestData["materialContext"]["materialId"] = (
        "MAT-NI-SULF"
    )

    with pytest.raises(
        ValidationError,
        match="materialId가 일치하지 않습니다",
    ):
        ErpExposureRequest.model_validate(requestData)

def testAsOfRequiresTimezone() -> None:
    """
    분석 기준 시각에 시간대가 없으면
    ValidationError가 발생해야 한다.
    """

    requestData = createCobaltRequestData()

    requestData["asOf"] = "2026-07-22T09:00:00"

    with pytest.raises(
        ValidationError,
        match="시간대 정보가 필요합니다",
    ):
        ErpExposureRequest.model_validate(requestData)

def testInvalidDependencyRatio() -> None:
    """
    공급사 의존도는 0~1 범위여야 한다.
    """

    requestData = createCobaltRequestData()

    requestData[
        "materialContext"
    ][
        "supplierDependencyRatio"
    ] = 1.5

    with pytest.raises(ValidationError):
        ErpExposureRequest.model_validate(requestData)

def testAlternativeSupplierContext() -> None:
    """대체 공급사 원본 목록을 받을 수 있어야 한다."""

    requestData = createCobaltRequestData()

    requestData["alternativeSuppliers"] = [
        {
            "supplierId": "SUP-COD-02",
            "contractId": "CTR-011",
            "supplierStatus": "ACTIVE",
            "availableCapacityQuantity": 7000,
            "leadTimeDays": 25,
            "qualificationStatus": (
                "CONDITIONAL"
            ),
        },
        {
            "supplierId": "SUP-CAN-01",
            "contractId": "CTR-012",
            "supplierStatus": "ACTIVE",
            "availableCapacityQuantity": None,
            "leadTimeDays": 30,
            "qualificationStatus": (
                "PENDING"
            ),
        },
    ]

    request = (
        ErpExposureRequest
        .model_validate(requestData)
    )

    assert len(
        request.alternativeSuppliers
    ) == 2

    firstSupplier = (
        request.alternativeSuppliers[0]
    )

    assert (
        firstSupplier.supplierId
        == "SUP-COD-02"
    )

    assert (
        firstSupplier.contractId
        == "CTR-011"
    )

    assert (
        firstSupplier
        .availableCapacityQuantity
        == 7000
    )

    assert (
        firstSupplier
        .qualificationStatus
        is SupplierQualificationStatus
        .CONDITIONAL
    )

    secondSupplier = (
        request.alternativeSuppliers[1]
    )

    assert (
        secondSupplier
        .availableCapacityQuantity
        is None
    )

def testNegativeAlternativeCapacity() -> None:
    """대체 공급 가능 수량은 음수가 될 수 없다."""

    requestData = createCobaltRequestData()

    requestData["alternativeSuppliers"] = [
        {
            "supplierId": "SUP-COD-02",
            "contractId": "CTR-011",
            "supplierStatus": "ACTIVE",
            "availableCapacityQuantity": -100,
            "leadTimeDays": 25,
            "qualificationStatus": (
                "APPROVED"
            ),
        }
    ]

    with pytest.raises(
        ValidationError
    ):
        ErpExposureRequest.model_validate(
            requestData
        )
