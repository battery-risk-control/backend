from datetime import datetime
from typing import Any

from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpExposureFacts,
    ErpExposureRequest,
    ErpPurchaseOrderContext,
    ErpRiskComponents,
    ExposureLevel,
    PurchaseOrderStatus,
)

from app.services.erp_rule_loader import (
    loadErpRules,
)

# ETA 계산에서 제외할 발주 상태
EXCLUDED_ETA_STATUSES = {
    PurchaseOrderStatus.CANCELLED,
    PurchaseOrderStatus.CLOSED,
}


# ERP 사실값 계산 함수
def calculateAvailableQuantity(
    onHandQuantity: float,
    reservedQuantity: float,
    blockedQuantity: float,
    qualityHoldQuantity: float,
) -> float:
    """
    실제로 사용할 수 있는 재고 수량을 계산한다.

    availableQuantity
    = onHandQuantity
    - reservedQuantity
    - blockedQuantity
    - qualityHoldQuantity
    """

    availableQuantity = (
        onHandQuantity
        - reservedQuantity
        - blockedQuantity
        - qualityHoldQuantity
    )

    return round(availableQuantity, 2)

def calculateInventoryDays(
    availableQuantity: float | None,
    averageDailyUsage: float | None,
) -> float | None:
    """
    현재 가용재고로 버틸 수 있는 일수를 계산한다.

    averageDailyUsage가 없거나 0이면 계산할 수 없으므로
    None을 반환한다.
    """

    if availableQuantity is None:
        return None

    if (
        averageDailyUsage is None
        or averageDailyUsage <= 0
    ):
        return None

    inventoryDays = (
        availableQuantity / averageDailyUsage
    )

    return round(inventoryDays, 2)

def calculateSafetyStockDays(
    safetyStockQuantity: float | None,
    averageDailyUsage: float | None,
) -> float | None:
    """
    안전재고 수량을 일수로 변환한다.

    safetyStockDays
    = safetyStockQuantity / averageDailyUsage
    """

    if safetyStockQuantity is None:
        return None

    if (
        averageDailyUsage is None
        or averageDailyUsage <= 0
    ):
        return None

    safetyStockDays = (
        safetyStockQuantity / averageDailyUsage
    )

    return round(safetyStockDays, 2)

def selectNextEligiblePurchaseOrder(
    purchaseOrders: list[ErpPurchaseOrderContext],
    materialId: str,
) -> ErpPurchaseOrderContext | None:
    """
    분석 대상 자재와 관련된 발주 중
    가장 빠른 유효 입고 예정 발주를 선택한다.

    선택 조건:
    1. 자재 ID가 분석 대상과 일치
    2. eligibleForEta가 true
    3. 잔여 발주 수량이 0보다 큼
    4. CLOSED 또는 CANCELLED가 아님
    5. effectiveArrivalDate가 존재함
    """

    eligiblePurchaseOrders = [
        purchaseOrder
        for purchaseOrder in purchaseOrders
        if (
            purchaseOrder.materialId == materialId
            and purchaseOrder.eligibleForEta
            and purchaseOrder.remainingQuantity > 0
            and purchaseOrder.orderStatus
            not in EXCLUDED_ETA_STATUSES
            and purchaseOrder.effectiveArrivalDate
            is not None
        )
    ]

    if not eligiblePurchaseOrders:
        return None

    return min(
        eligiblePurchaseOrders,
        key=lambda purchaseOrder: (
            purchaseOrder.effectiveArrivalDate
        ),
    )

def calculateNextEtaDays(
    asOf: datetime,
    purchaseOrder: (
        ErpPurchaseOrderContext | None
    ),
) -> int | None:
    """
    분석 기준일로부터 입고 예정일까지
    남은 날짜를 계산한다.

    유효한 발주가 없으면 None을 반환한다.
    ETA가 이미 지난 경우에는 0일로 반환한다.
    """

    if purchaseOrder is None:
        return None

    if purchaseOrder.effectiveArrivalDate is None:
        return None

    etaDays = (
        purchaseOrder.effectiveArrivalDate
        - asOf.date()
    ).days

    return max(0, etaDays)

def calculateEtaOverdueDays(
    asOf: datetime,
    purchaseOrder: (
        ErpPurchaseOrderContext | None
    ),
) -> int | None:
    """
    선택된 발주의 ETA가 며칠 지났는지 계산한다.
    """

    if purchaseOrder is None:
        return None

    if (
        purchaseOrder.effectiveArrivalDate
        is None
    ):
        return None

    overdueDays = (
        asOf.date()
        - purchaseOrder.effectiveArrivalDate
    ).days

    return max(0, overdueDays)

def calculateExpectedSupplyGapDays(
    inventoryDays: float | None,
    nextEtaDays: int | None,
) -> float | None:
    """
    재고가 소진된 후 다음 입고까지 예상되는
    공급 공백일수를 계산한다.

    expectedSupplyGapDays
    = max(0, nextEtaDays - inventoryDays)
    """

    if inventoryDays is None:
        return None

    if nextEtaDays is None:
        return None

    expectedSupplyGapDays = max(
        0,
        nextEtaDays - inventoryDays,
    )

    return round(expectedSupplyGapDays, 2)

def calculateProjectedSupplyGapDays(
    asOf: datetime,
    availableQuantity: float | None,
    averageDailyUsage: float | None,
    purchaseOrders: list[
        ErpPurchaseOrderContext
    ],
    materialId: str,
) -> float | None:
    """
    발주별 입고일과 잔여수량을 날짜순으로
    누적 반영해 공급 공백을 계산한다.
    """

    if availableQuantity is None:
        return None

    if (
        averageDailyUsage is None
        or averageDailyUsage <= 0
    ):
        return None

    eligiblePurchaseOrders = sorted(
        [
            purchaseOrder
            for purchaseOrder
            in purchaseOrders
            if (
                purchaseOrder.materialId
                == materialId
                and (
                    purchaseOrder
                    .eligibleForEta
                )
                and (
                    purchaseOrder
                    .remainingQuantity
                    > 0
                )
                and (
                    purchaseOrder.orderStatus
                    not in EXCLUDED_ETA_STATUSES
                )
                and (
                    purchaseOrder
                    .effectiveArrivalDate
                    is not None
                )
                and (
                    purchaseOrder
                    .effectiveArrivalDate
                    >= asOf.date()
                )
            )
        ],
        key=lambda purchaseOrder: (
            purchaseOrder
            .effectiveArrivalDate
        ),
    )

    if not eligiblePurchaseOrders:
        return None

    projectedQuantity = (
        availableQuantity
    )

    previousDate = asOf.date()
    totalGapDays = 0.0

    for purchaseOrder in (
        eligiblePurchaseOrders
    ):
        arrivalDate = (
            purchaseOrder
            .effectiveArrivalDate
        )

        intervalDays = (
            arrivalDate - previousDate
        ).days

        requiredQuantity = (
            averageDailyUsage
            * intervalDays
        )

        if (
            projectedQuantity
            >= requiredQuantity
        ):
            projectedQuantity -= (
                requiredQuantity
            )

        else:
            coveredDays = (
                projectedQuantity
                / averageDailyUsage
            )

            uncoveredDays = max(
                0,
                intervalDays
                - coveredDays,
            )

            totalGapDays += (
                uncoveredDays
            )

            projectedQuantity = 0

        projectedQuantity += (
            purchaseOrder
            .remainingQuantity
        )

        previousDate = arrivalDate

    return round(totalGapDays, 2)

def calculateErpFacts(
    request: ErpExposureRequest,
) -> ErpExposureFacts:
    """
    ERP Exposure Request를 이용해
    ERP의 결정론적 사실값을 계산한다.

    이 함수는 위험점수나 등급을 계산하지 않는다.
    """

    if not request.erpAnalysisRequired:
        raise ValueError(
            "ERP 분석이 필요하지 않은 요청입니다."
        )

    if request.materialContext is None:
        raise ValueError(
            "ERP 분석에 materialContext가 필요합니다."
        )

    materialContext = request.materialContext

    # 차감할 재고가 현재고보다 큰 비정상 데이터
    rawAvailableQuantity = (
        calculateAvailableQuantity(
            onHandQuantity=(
                materialContext.onHandQuantity
            ),
            reservedQuantity=(
                materialContext.reservedQuantity
            ),
            blockedQuantity=(
                materialContext.blockedQuantity
            ),
            qualityHoldQuantity=(
                materialContext.qualityHoldQuantity
            ),
        )
    )

    availableQuantity = (
        rawAvailableQuantity
        if rawAvailableQuantity >= 0
        else None
    )

    inventoryDays = calculateInventoryDays(
        availableQuantity=availableQuantity,
        averageDailyUsage=(
            materialContext.averageDailyUsage
        ),
    )

    safetyStockDays = calculateSafetyStockDays(
        safetyStockQuantity=(
            materialContext.safetyStockQuantity
        ),
        averageDailyUsage=(
            materialContext.averageDailyUsage
        ),
    )

    selectedPurchaseOrder = (
        selectNextEligiblePurchaseOrder(
            purchaseOrders=request.purchaseOrders,
            materialId=(
                materialContext.materialId
            ),
        )
    )

    selectedPurchaseOrderOriginalStatus = (
        selectedPurchaseOrder.orderStatus
        if selectedPurchaseOrder
        else None
    )

    selectedPurchaseOrderStatus = (
        normalizePurchaseOrderStatus(
            purchaseOrder=selectedPurchaseOrder,
            asOf=request.asOf,
        )
        if selectedPurchaseOrder
        else None
    )

    nextEtaDays = calculateNextEtaDays(
        asOf=request.asOf,
        purchaseOrder=selectedPurchaseOrder,
    )

    etaOverdueDays = (
        calculateEtaOverdueDays(
            asOf=request.asOf,
            purchaseOrder=(
                selectedPurchaseOrder
            ),
    )
)

    expectedSupplyGapDays = (
        calculateExpectedSupplyGapDays(
            inventoryDays=inventoryDays,
            nextEtaDays=nextEtaDays,
        )
    )

    projectedSupplyGapDays = (
        calculateProjectedSupplyGapDays(
            asOf=request.asOf,
            availableQuantity=(
                availableQuantity
            ),
            averageDailyUsage=(
                materialContext
                .averageDailyUsage
            ),
            purchaseOrders=(
                request.purchaseOrders
            ),
            materialId=(
                materialContext.materialId
            ),
        )
)

    return ErpExposureFacts(
        availableQuantity=availableQuantity,
        inventoryDays=inventoryDays,
        safetyStockDays=safetyStockDays,
        nextEtaDays=nextEtaDays,
        etaOverdueDays=etaOverdueDays,
        expectedSupplyGapDays=(
            expectedSupplyGapDays
        ),
        projectedSupplyGapDays=projectedSupplyGapDays,
        supplierDependencyRatio=(
            materialContext.supplierDependencyRatio
        ),
        selectedPurchaseOrderId=(
            selectedPurchaseOrder.purchaseOrderId
            if selectedPurchaseOrder
            else None
        ),
        selectedPurchaseOrderItemId=(
            selectedPurchaseOrder.purchaseOrderItemId
            if selectedPurchaseOrder
            else None
        ),
        selectedPurchaseOrderOriginalStatus=(
            selectedPurchaseOrderStatus
        ),
        selectedPurchaseOrderStatus=(
            selectedPurchaseOrderStatus
        ),
    )


# 세부 위험점수 함수
def calculateSupplyGapRiskScore(
    expectedSupplyGapDays: float | None,
    rules: dict[str, Any],
) -> float | None:
    """예상 공급 공백일수를 위험점수로 변환한다."""

    if expectedSupplyGapDays is None:
        return None

    gapRules = rules["supplyGapRisk"]

    if (
        expectedSupplyGapDays
        <= gapRules["noGap"]["maxDays"]
    ):
        return float(
            gapRules["noGap"]["score"]
        )

    if (
        expectedSupplyGapDays
        <= gapRules["shortGap"]["maxDays"]
    ):
        return float(
            gapRules["shortGap"]["score"]
        )

    if (
        expectedSupplyGapDays
        <= gapRules["mediumGap"]["maxDays"]
    ):
        return float(
            gapRules["mediumGap"]["score"]
        )

    return float(
        gapRules["longGap"]["score"]
    )

def calculateSafetyStockRiskScore(
    inventoryDays: float | None,
    safetyStockDays: float | None,
    rules: dict[str, Any],
) -> float | None:
    """
    현재 재고일수와 안전재고일수의 비율을
    위험점수로 변환한다.
    """

    if inventoryDays is None:
        return None

    if (
        safetyStockDays is None
        or safetyStockDays <= 0
    ):
        return None

    stockRatio = (
        inventoryDays / safetyStockDays
    )

    safetyRules = rules["safetyStockRisk"]

    if (
        stockRatio
        >= safetyRules["sufficient"]["minRatio"]
    ):
        return float(
            safetyRules["sufficient"]["score"]
        )

    if (
        stockRatio
        >= safetyRules["belowSafety"]["minRatio"]
    ):
        return float(
            safetyRules["belowSafety"]["score"]
        )

    return float(
        safetyRules["severeShortage"]["score"]
    )

def calculateDependencyRiskScore(
    supplierDependencyRatio: float | None,
    rules: dict[str, Any],
) -> float | None:
    """주 공급사 의존도를 위험점수로 변환한다."""

    if supplierDependencyRatio is None:
        return None

    dependencyRules = rules[
        "supplierDependencyRisk"
    ]

    if (
        supplierDependencyRatio
        < dependencyRules[
            "low"
        ]["maxRatioExclusive"]
    ):
        return float(
            dependencyRules["low"]["score"]
        )

    if (
        supplierDependencyRatio
        < dependencyRules[
            "medium"
        ]["maxRatioExclusive"]
    ):
        return float(
            dependencyRules["medium"]["score"]
        )

    return float(
        dependencyRules["high"]["score"]
    )

def calculatePurchaseOrderDelayRiskScore(
    purchaseOrderStatus: (
        PurchaseOrderStatus | None
    ),
    rules: dict[str, Any],
) -> float | None:
    """대표 발주 상태를 위험점수로 변환한다."""

    if purchaseOrderStatus is None:
        return None

    statusRules = rules[
        "purchaseOrderDelayRisk"
    ]

    statusValue = purchaseOrderStatus.value

    if statusValue not in statusRules:
        raise ValueError(
            "발주 상태에 대응하는 위험 규칙이 없습니다: "
            f"{statusValue}"
        )

    return float(statusRules[statusValue])

def calculateAlternativeSupplierRiskScore(
    alternativeSupplierStatus: (
        AlternativeSupplierStatus | None
    ),
    rules: dict[str, Any],
) -> float | None:
    """대체 공급사 승인 상태를 위험점수로 변환한다."""

    if alternativeSupplierStatus is None:
        return None

    statusRules = rules[
        "alternativeSupplierRisk"
    ]

    statusValue = (
        alternativeSupplierStatus.value
    )

    if statusValue not in statusRules:
        raise ValueError(
            "대체 공급사 상태에 대응하는 "
            "위험 규칙이 없습니다: "
            f"{statusValue}"
        )

    return float(statusRules[statusValue])


# 위험요소 묶음 계산
def calculateErpRiskComponents(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    rules: dict[str, Any] | None = None,
) -> ErpRiskComponents:
    """ERP의 다섯 가지 세부 위험점수를 계산한다."""

    if request.materialContext is None:
        return ErpRiskComponents()

    if rules is None:
        rules = loadErpRules()

    materialContext = request.materialContext

    return ErpRiskComponents(
        gapRiskScore=(
            calculateSupplyGapRiskScore(
                expectedSupplyGapDays=(
                    facts.expectedSupplyGapDays
                ),
                rules=rules,
            )
        ),
        safetyStockRiskScore=(
            calculateSafetyStockRiskScore(
                inventoryDays=facts.inventoryDays,
                safetyStockDays=(
                    facts.safetyStockDays
                ),
                rules=rules,
            )
        ),
        dependencyRiskScore=(
            calculateDependencyRiskScore(
                supplierDependencyRatio=(
                    facts.supplierDependencyRatio
                ),
                rules=rules,
            )
        ),
        purchaseOrderDelayRiskScore=(
            calculatePurchaseOrderDelayRiskScore(
                purchaseOrderStatus=(
                    facts.selectedPurchaseOrderStatus
                ),
                rules=rules,
            )
        ),
        alternativeSupplierRiskScore=(
            calculateAlternativeSupplierRiskScore(
                alternativeSupplierStatus=(
                    materialContext
                    .alternativeSupplierStatus
                ),
                rules=rules,
            )
        ),
        # 최초 계산 시점엔 Contract Agent가 아직 안 돌아서 항상 None.
        # erp_recheck 협상 라운드에서 contract_gap_score가 나오면 그 값을
        # 그대로 이 컴포넌트로 채워서 재계산한다(soojung_adapter.recheck_soojung_erp_node).
        contractProtectionRiskScore=None,
    )

# ERP Exposure Score 계산
def calculateErpExposureScore(
    riskComponents: ErpRiskComponents,
    rules: dict[str, Any] | None = None,
) -> float | None:
    """
    세부 위험점수의 가중합을 계산한다.

    contractProtectionRiskScore가 없으면(최초 계산 — Contract Agent가 아직 안 돎)
    나머지 5개 가중치만으로 계산하되, 5개 가중치 합(0.85)으로 나눠 합이 1.0이
    되도록 재정규화한다. contractProtectionRiskScore가 채워지면(협상 라운드 재계산)
    6개 전부를 rules["weights"] 그대로 사용한다.
    """

    if rules is None:
        rules = loadErpRules()

    baseComponents = {
        "supplyGap": riskComponents.gapRiskScore,
        "safetyStock": riskComponents.safetyStockRiskScore,
        "supplierDependency": (
            riskComponents.dependencyRiskScore
        ),
        "purchaseOrderDelay": (
            riskComponents.purchaseOrderDelayRiskScore
        ),
        "alternativeSupplier": (
            riskComponents.alternativeSupplierRiskScore
        ),
    }

    # 5개 필수 컴포넌트 중 하나라도 없으면 최종 점수를 만들지 않는다.
    if any(
        value is None
        for value in baseComponents.values()
    ):
        return None

    weights = rules["weights"]

    if riskComponents.contractProtectionRiskScore is None:
        baseWeightSum = sum(
            weights[name]
            for name in baseComponents
        )

        score = sum(
            value * (weights[name] / baseWeightSum)
            for name, value in baseComponents.items()
        )

        return round(score, 2)

    componentValues = {
        **baseComponents,
        "contractProtection": (
            riskComponents.contractProtectionRiskScore
        ),
    }

    score = sum(
        value * weights[name]
        for name, value in componentValues.items()
    )

    return round(score, 2)

# 강제 Critical 계산
def determineForcedCritical(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    rules: dict[str, Any] | None = None,
) -> bool:
    """강제 CRITICAL 조건을 검사한다."""

    if request.materialContext is None:
        return False

    if rules is None:
        rules = loadErpRules()

    materialContext = request.materialContext

    forcedRules = rules[
        "forcedCriticalRules"
    ]

    # 다음 입고 전에 재고 소진
    stockoutRule = forcedRules[
        "stockoutBeforeNextEta"
    ]

    if (
        stockoutRule["enabled"]
        and facts.expectedSupplyGapDays is not None
        and facts.expectedSupplyGapDays
        > stockoutRule[
            "minimumGapDaysExclusive"
        ]
    ):
        return True

    # 거래 중지 공급사에 90% 이상 의존
    suspendedRule = forcedRules[
        "suspendedSupplierDependency"
    ]

    supplierStatus = (
        materialContext.supplierStatus.value
        if materialContext.supplierStatus
        else None
    )

    if (
        suspendedRule["enabled"]
        and facts.supplierDependencyRatio
        is not None
        and facts.supplierDependencyRatio
        >= suspendedRule[
            "minimumDependencyRatio"
        ]
        and supplierStatus
        in suspendedRule["supplierStatuses"]
    ):
        return True

    # 대체 공급사 없음 + 안전재고 미달
    noAlternativeRule = forcedRules[
        "noAlternativeBelowSafetyStock"
    ]

    alternativeStatus = (
        materialContext
        .alternativeSupplierStatus.value
        if materialContext
        .alternativeSupplierStatus
        else None
    )

    if (
        noAlternativeRule["enabled"]
        and alternativeStatus
        in noAlternativeRule[
            "alternativeSupplierStatuses"
        ]
        and facts.inventoryDays is not None
        and facts.safetyStockDays is not None
        and facts.inventoryDays
        < facts.safetyStockDays
    ):
        return True

    return False

# 강제 Warning 계산
def determineForcedWarning(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    rules: dict[str, Any] | None = None,
) -> bool:
    """기본점수 외의 WARNING 조건을 검사한다."""

    if request.materialContext is None:
        return False

    if rules is None:
        rules = loadErpRules()

    materialContext = request.materialContext

    warningRules = rules[
        "forcedWarningRules"
    ]

    # 안전재고 미달
    inventoryRule = warningRules[
        "inventoryBelowSafetyStock"
    ]

    if (
        inventoryRule["enabled"]
        and facts.inventoryDays is not None
        and facts.safetyStockDays is not None
        and facts.inventoryDays
        < facts.safetyStockDays
    ):
        return True

    # 부분입고
    partialRule = warningRules[
        "partiallyReceivedPurchaseOrder"
    ]

    purchaseOrderStatus = (
        facts.selectedPurchaseOrderStatus.value
        if facts.selectedPurchaseOrderStatus
        else None
    )

    if (
        partialRule["enabled"]
        and purchaseOrderStatus
        in partialRule[
            "purchaseOrderStatuses"
        ]
    ):
        return True

    # 조건부 또는 승인 대기 대체 공급사
    alternativeRule = warningRules[
        "conditionalAlternativeSupplier"
    ]

    alternativeStatus = (
        materialContext
        .alternativeSupplierStatus.value
        if materialContext
        .alternativeSupplierStatus
        else None
    )

    if (
        alternativeRule["enabled"]
        and alternativeStatus
        in alternativeRule[
            "alternativeSupplierStatuses"
        ]
    ):
        return True

    return False

# 최종 ERP 노출등급 계산
def determineExposureLevel(
    erpExposureScore: float | None,
    forcedCritical: bool,
    forcedWarning: bool,
    rules: dict[str, Any] | None = None,
) -> ExposureLevel:
    """ERP 점수와 강제 조건을 이용해 등급을 결정한다."""

    if rules is None:
        rules = loadErpRules()

    # 점수를 계산할 수 없으면 UNKNOWN
    if erpExposureScore is None:
        return ExposureLevel.UNKNOWN

    if forcedCritical:
        return ExposureLevel.CRITICAL

    thresholds = rules[
        "exposureLevelThresholds"
    ]

    if (
        erpExposureScore
        >= thresholds["critical"]
    ):
        return ExposureLevel.CRITICAL

    if (
        forcedWarning
        or erpExposureScore
        >= thresholds["warning"]
    ):
        return ExposureLevel.WARNING

    return ExposureLevel.NORMAL

# 상태 정규화 함수
def normalizePurchaseOrderStatus(
    purchaseOrder: ErpPurchaseOrderContext,
    asOf: datetime,
) -> PurchaseOrderStatus:
    """
    ETA가 지났지만 미입고 수량이 남은 발주를
    DELAYED 상태로 정규화한다.
    """

    status = purchaseOrder.orderStatus

    if (
        purchaseOrder.effectiveArrivalDate
        is None
    ):
        return status

    overdue = (
        purchaseOrder.effectiveArrivalDate
        < asOf.date()
    )

    delayCandidateStatuses = {
        PurchaseOrderStatus.OPEN,
        PurchaseOrderStatus.CONFIRMED,
        PurchaseOrderStatus.PARTIALLY_RECEIVED,
    }

    if (
        overdue
        and purchaseOrder.remainingQuantity > 0
        and status in delayCandidateStatuses
    ):
        return PurchaseOrderStatus.DELAYED

    return status
