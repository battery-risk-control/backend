from app.schemas.erp import (
    ContractQuestionCode,
    DataQualityStatus,
    ErpExposureRequest,
    ErpPurchaseOrderContext,
    ExposureLevel,
    PurchaseOrderStatus,
)

from app.services.erp_calculator import (
    calculateAvailableQuantity,
    calculateErpFacts,
    calculateEtaOverdueDays,
    calculateExpectedSupplyGapDays,
    calculateInventoryDays,
    calculateSafetyStockDays,
    normalizePurchaseOrderStatus,
    selectNextEligiblePurchaseOrder,
    calculateProjectedSupplyGapDays,
)

from app.services.erp_exposure_service import (
    calculateErpExposure,
)

from datetime import date, datetime


def createCobaltRequest() -> ErpExposureRequest:
    requestData = {
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
        "eventSummary": "코발트 선적 지연",
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
                "purchaseOrderItemId": "POI-CLOSED",
                "purchaseOrderId": "PO-CLOSED",
                "materialId": "MAT-CO-SULF",
                "supplierId": "SUP-COD-01",
                "contractId": "CTR-010",
                "remainingQuantity": 0,
                "orderStatus": "CLOSED",
                "effectiveArrivalDate": "2026-07-25",
                "eligibleForEta": False,
            },
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
            },
            {
                "purchaseOrderItemId": "POI-LATER",
                "purchaseOrderId": "PO-LATER",
                "materialId": "MAT-CO-SULF",
                "supplierId": "SUP-COD-02",
                "contractId": "CTR-011",
                "remainingQuantity": 10000,
                "orderStatus": "CONFIRMED",
                "effectiveArrivalDate": "2026-08-15",
                "eligibleForEta": True,
            },
        ],
    }

    return ErpExposureRequest.model_validate(
        requestData
    )

def testCalculateAvailableQuantity() -> None:
    result = calculateAvailableQuantity(
        onHandQuantity=7280,
        reservedQuantity=429,
        blockedQuantity=195,
        qualityHoldQuantity=156,
    )

    assert result == 6500

def testCalculateInventoryDays() -> None:
    result = calculateInventoryDays(
        availableQuantity=6500,
        averageDailyUsage=1000,
    )

    assert result == 6.5

def testInventoryDaysWithoutUsage() -> None:
    result = calculateInventoryDays(
        availableQuantity=6500,
        averageDailyUsage=None,
    )

    assert result is None

def testCalculateSafetyStockDays() -> None:
    result = calculateSafetyStockDays(
        safetyStockQuantity=18000,
        averageDailyUsage=1000,
    )

    assert result == 18

def testSelectNextEligiblePurchaseOrder() -> None:
    request = createCobaltRequest()

    selected = selectNextEligiblePurchaseOrder(
        purchaseOrders=request.purchaseOrders,
        materialId="MAT-CO-SULF",
    )

    assert selected is not None
    assert selected.purchaseOrderId == "PO-0004"

def testCalculateExpectedSupplyGapDays() -> None:
    result = calculateExpectedSupplyGapDays(
        inventoryDays=6.5,
        nextEtaDays=15,
    )

    assert result == 8.5

def testCalculateErpFacts() -> None:
    request = createCobaltRequest()

    facts = calculateErpFacts(request)

    assert facts.availableQuantity == 6500
    assert facts.inventoryDays == 6.5
    assert facts.safetyStockDays == 18
    assert facts.nextEtaDays == 15
    assert facts.expectedSupplyGapDays == 8.5
    assert (
        facts.supplierDependencyRatio
        == 0.84
    )
    assert (
        facts.selectedPurchaseOrderId
        == "PO-0004"
    )
    assert (
        facts.selectedPurchaseOrderItemId
        == "POI-0004"
    )


# CRITICAL 테스트
def testCalculateCobaltErpExposure() -> None:
    request = createCobaltRequest()

    result = calculateErpExposure(request)

    assert result.requestId == "ERP-REQ-004"

    assert (
        result.facts.availableQuantity
        == 6500
    )

    assert result.facts.inventoryDays == 6.5
    assert result.facts.safetyStockDays == 18
    assert result.facts.nextEtaDays == 15

    assert (
        result.facts.expectedSupplyGapDays
        == 8.5
    )

    assert result.erpExposureScore == 100

    assert (
        result.exposureLevel
        is ExposureLevel.CRITICAL
    )

    assert result.forcedCritical is True

    assert (
        result.contractReviewRequired
        is True
    )

    assert (
        result.manualReviewRequired
        is False
    )

    assert (
        result.dataQualityStatus
        is DataQualityStatus.VALID
    )

    questionCodes = {
        question.questionCode
        for question
        in result.questionsForContractAgent
    }

    assert (
        ContractQuestionCode
        .DELIVERY_DELAY_NOTICE
        in questionCodes
    )

    assert (
        ContractQuestionCode
        .DELIVERY_PENALTY
        in questionCodes
    )

    assert (
        ContractQuestionCode.FORCE_MAJEURE
        in questionCodes
    )

    assert (
        ContractQuestionCode
        .ALTERNATIVE_SUPPLIER_RESTRICTION
        in questionCodes
    )

# 데이터 누락 테스트
def testIncompleteErpExposure() -> None:
    request = createCobaltRequest()

    incompleteContext = (
        request.materialContext.model_copy(
            update={
                "averageDailyUsage": None,
            }
        )
    )

    incompleteRequest = request.model_copy(
        update={
            "materialContext": (
                incompleteContext
            ),
            "purchaseOrders": [],
        }
    )

    result = calculateErpExposure(
        incompleteRequest
    )

    assert result.erpExposureScore is None

    assert (
        result.exposureLevel
        is ExposureLevel.UNKNOWN
    )

    assert (
        result.dataQualityStatus
        is DataQualityStatus.INCOMPLETE
    )

    assert (
        result.manualReviewRequired
        is True
    )

    assert (
        result.contractReviewRequired
        is False
    )

    assert result.warnings

def testNegativeAvailableQuantityReturnsInvalid():
    request = createCobaltRequest()

    materialContext = (
        request.materialContext
    )

    assert materialContext is not None

    materialContext.onHandQuantity = 100
    materialContext.reservedQuantity = 80
    materialContext.blockedQuantity = 30
    materialContext.qualityHoldQuantity = 10

    result = calculateErpExposure(
        request
    )

    assert (
        result.facts.availableQuantity
        is None
    )

    assert (
        result.facts.inventoryDays
        is None
    )

    assert (
        result.dataQualityStatus
        == DataQualityStatus.INVALID
    )

    assert (
        result.exposureLevel
        == ExposureLevel.UNKNOWN
    )

    assert (
        result.erpExposureScore
        is None
    )

    assert (
        result.manualReviewRequired
        is True
    )


# 발주 상태 정규화 테스트
def testPastEtaOpenOrderIsNormalizedToDelayed():
    purchaseOrder = (
        ErpPurchaseOrderContext(
            purchaseOrderItemId=(
                "POI-TEST-001"
            ),
            purchaseOrderId="PO-TEST-001",
            materialId="MAT-TEST",
            supplierId="SUP-TEST",
            contractId="CTR-TEST",
            remainingQuantity=1000,
            orderStatus=(
                PurchaseOrderStatus.OPEN
            ),
            effectiveArrivalDate=date(
                2026,
                7,
                20,
            ),
            eligibleForEta=True,
        )
    )

    asOf = datetime.fromisoformat(
        "2026-07-22T09:00:00+09:00"
    )

    normalizedStatus = (
        normalizePurchaseOrderStatus(
            purchaseOrder=purchaseOrder,
            asOf=asOf,
        )
    )

    assert (
        normalizedStatus
        == PurchaseOrderStatus.DELAYED
    )

# ETA 초과 일수 테스트
def testCalculateEtaOverdueDays():
    purchaseOrder = (
        ErpPurchaseOrderContext(
            purchaseOrderItemId=(
                "POI-TEST-001"
            ),
            purchaseOrderId="PO-TEST-001",
            materialId="MAT-TEST",
            supplierId="SUP-TEST",
            contractId="CTR-TEST",
            remainingQuantity=1000,
            orderStatus=(
                PurchaseOrderStatus.OPEN
            ),
            effectiveArrivalDate=date(
                2026,
                7,
                20,
            ),
            eligibleForEta=True,
        )
    )

    asOf = datetime.fromisoformat(
        "2026-07-22T09:00:00+09:00"
    )

    result = calculateEtaOverdueDays(
        asOf=asOf,
        purchaseOrder=purchaseOrder,
    )

    assert result == 2

# 정상적으로 미래에 입고되는 발주 테스트
def testFutureEtaIsNotOverdue():
    purchaseOrder = (
        ErpPurchaseOrderContext(
            purchaseOrderItemId=(
                "POI-TEST-002"
            ),
            purchaseOrderId="PO-TEST-002",
            materialId="MAT-TEST",
            supplierId="SUP-TEST",
            contractId="CTR-TEST",
            remainingQuantity=1000,
            orderStatus=(
                PurchaseOrderStatus.CONFIRMED
            ),
            effectiveArrivalDate=date(
                2026,
                7,
                25,
            ),
            eligibleForEta=True,
        )
    )

    asOf = datetime.fromisoformat(
        "2026-07-22T09:00:00+09:00"
    )

    normalizedStatus = (
        normalizePurchaseOrderStatus(
            purchaseOrder=purchaseOrder,
            asOf=asOf,
        )
    )

    overdueDays = (
        calculateEtaOverdueDays(
            asOf=asOf,
            purchaseOrder=purchaseOrder,
        )
    )

    assert (
        normalizedStatus
        == PurchaseOrderStatus.CONFIRMED
    )

    assert overdueDays == 0

# 누적 투영 테스트
def testSmallFirstReceiptDoesNotResolveGap():
    purchaseOrders = [
        ErpPurchaseOrderContext(
            purchaseOrderItemId=(
                "POI-TEST-001"
            ),
            purchaseOrderId="PO-TEST-001",
            materialId="MAT-TEST",
            supplierId="SUP-TEST",
            contractId="CTR-TEST",
            remainingQuantity=100,
            orderStatus=(
                PurchaseOrderStatus.CONFIRMED
            ),
            effectiveArrivalDate=date(
                2026,
                7,
                27,
            ),
            eligibleForEta=True,
        ),
        ErpPurchaseOrderContext(
            purchaseOrderItemId=(
                "POI-TEST-002"
            ),
            purchaseOrderId="PO-TEST-002",
            materialId="MAT-TEST",
            supplierId="SUP-TEST",
            contractId="CTR-TEST",
            remainingQuantity=20000,
            orderStatus=(
                PurchaseOrderStatus.CONFIRMED
            ),
            effectiveArrivalDate=date(
                2026,
                8,
                1,
            ),
            eligibleForEta=True,
        ),
    ]

    result = (
        calculateProjectedSupplyGapDays(
            asOf=datetime.fromisoformat(
                "2026-07-22T09:00:00+09:00"
            ),
            availableQuantity=5000,
            averageDailyUsage=1000,
            purchaseOrders=purchaseOrders,
            materialId="MAT-TEST",
        )
    )

    assert result == 4.9
