from typing import Any

from app.adapters.erp_agent_adapter import (
    adaptErpExposureRequest,
)

from app.schemas.erp import (
    CalculationEvidence,
    ContractQuestion,
    ContractQuestionCode,
    DataQualityStatus,
    ErpExposureFacts,
    ErpExposureRequest,
    ErpExposureResponse,
    ErpRiskComponents,
    ExposureLevel,
)

from app.services.erp_calculator import (
    calculateAvailableQuantity,
    calculateErpExposureScore,
    calculateErpFacts,
    calculateErpRiskComponents,
    determineExposureLevel,
    determineForcedCritical,
    determineForcedWarning,
)

from app.services.erp_rule_loader import (
    loadErpRules,
)

from app.services.erp_supplier_assessment_service import (
    buildSupplierAssessments,
)


QUESTION_TEXTS = {
    ContractQuestionCode.DELIVERY_DELAY_NOTICE: (
        "계약서에 납기 지연 발생 시 "
        "공급사의 통보 의무가 있는가?"
    ),
    ContractQuestionCode.DELIVERY_PENALTY: (
        "계약서에 납기 지연 위약금 또는 "
        "손해배상 조항이 있는가?"
    ),
    ContractQuestionCode.FORCE_MAJEURE: (
        "현재 공급 차질에 적용할 수 있는 "
        "불가항력 조항이 있는가?"
    ),
    ContractQuestionCode.ALTERNATIVE_SUPPLIER_RESTRICTION: (
        "대체 공급사를 통한 구매를 제한하거나 "
        "사전 승인을 요구하는 조항이 있는가?"
    ),
    ContractQuestionCode.EMERGENCY_PURCHASE: (
        "공급 차질 발생 시 긴급 구매를 허용하는 "
        "조항이 있는가?"
    ),
    ContractQuestionCode.MINIMUM_PURCHASE_OBLIGATION: (
        "최소 구매수량 또는 최소 구매금액 의무가 "
        "대체 조달에 영향을 주는가?"
    ),
}


def uniqueValues(
    values: list[str | None],
) -> list[str]:
    """None과 중복을 제거하되 기존 순서를 유지한다."""

    result: list[str] = []

    for value in values:
        if value is None:
            continue

        if value not in result:
            result.append(value)

    return result

def evaluateDataQuality(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    rules: dict[str, Any],
) -> tuple[DataQualityStatus, list[str]]:
    """ERP 계산에 사용된 데이터의 품질을 판정한다."""

    warnings: list[str] = []

    if request.materialContext is None:
        return (
            DataQualityStatus.INCOMPLETE,
            ["materialContext가 누락되었습니다."],
        )

    context = request.materialContext

    rawAvailableQuantity = (
        calculateAvailableQuantity(
            onHandQuantity=context.onHandQuantity,
            reservedQuantity=(
                context.reservedQuantity
            ),
            blockedQuantity=(
                context.blockedQuantity
            ),
            qualityHoldQuantity=(
                context.qualityHoldQuantity
            ),
        )
    )

    if rawAvailableQuantity < 0:
        return (
            DataQualityStatus.INVALID,
            [
                "예약·차단·품질 보류 재고의 합이 "
                "현재고보다 큽니다."
            ],
        )

    missingFields: list[str] = []

    if (
        context.averageDailyUsage is None
        or context.averageDailyUsage <= 0
    ):
        missingFields.append(
            "averageDailyUsage"
        )

    if context.safetyStockQuantity is None:
        missingFields.append(
            "safetyStockQuantity"
        )

    if context.supplierDependencyRatio is None:
        missingFields.append(
            "supplierDependencyRatio"
        )

    if (
        context.alternativeSupplierStatus
        is None
    ):
        missingFields.append(
            "alternativeSupplierStatus"
        )

    if facts.nextEtaDays is None:
        missingFields.append(
            "effectiveArrivalDate"
        )

    if facts.selectedPurchaseOrderStatus is None:
            missingFields.append(
                "selectedPurchaseOrderStatus"
            )

    if missingFields:
        warnings.append(
            "ERP 필수 데이터가 누락되었습니다: "
            + ", ".join(missingFields)
        )

        return (
            DataQualityStatus.INCOMPLETE,
            warnings,
        )

    # 재고 스냅샷 시각이 있으면 신선도 검사
    if context.inventorySnapshotAt is not None:
        snapshotAt = context.inventorySnapshotAt

        if snapshotAt.tzinfo is None:
            return (
                DataQualityStatus.INVALID,
                [
                    "inventorySnapshotAt에 "
                    "시간대 정보가 없습니다."
                ],
            )

        inventoryAgeHours = (
            request.asOf - snapshotAt
        ).total_seconds() / 3600

        maximumAgeHours = rules[
            "dataQualityRules"
        ][
            "maximumInventoryAgeHours"
        ]

        if inventoryAgeHours < 0:
            return (
                DataQualityStatus.INVALID,
                [
                    "재고 스냅샷 시각이 "
                    "분석 기준 시각보다 미래입니다."
                ],
            )

        if inventoryAgeHours > maximumAgeHours:
            warnings.append(
                "재고 스냅샷이 "
                f"{inventoryAgeHours:.1f}시간 경과했습니다."
            )

            return (
                DataQualityStatus.STALE,
                warnings,
            )

    else:
        # 현재 Mock Agent CSV에는 Snapshot ID/시각이
        # 없을 수 있으므로 경고만 추가한다.
        warnings.append(
            "inventorySnapshotAt이 없어 "
            "재고 신선도를 확인하지 못했습니다."
        )

    return DataQualityStatus.VALID, warnings

def determineContractReviewRequired(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    exposureLevel: ExposureLevel,
    rules: dict[str, Any],
) -> bool:
    """ERP 결과를 기반으로 계약 검토 필요성을 결정한다."""

    if request.materialContext is None:
        return False

    context = request.materialContext
    contractRules = rules[
        "contractReviewRules"
    ]

    if (
        exposureLevel.value
        in contractRules["exposureLevels"]
    ):
        return True

    purchaseOrderStatus = (
        facts.selectedPurchaseOrderStatus.value
        if facts.selectedPurchaseOrderStatus
        else None
    )

    if (
        purchaseOrderStatus
        in contractRules[
            "purchaseOrderStatuses"
        ]
    ):
        return True

    alternativeStatus = (
        context.alternativeSupplierStatus.value
        if context.alternativeSupplierStatus
        else None
    )

    if (
        alternativeStatus
        in contractRules[
            "alternativeSupplierStatuses"
        ]
    ):
        return True

    supplierStatus = (
        context.supplierStatus.value
        if context.supplierStatus
        else None
    )

    if (
        supplierStatus
        in contractRules[
            "supplierStatuses"
        ]
    ):
        return True

    return False

def buildContractQuestions(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    contractReviewRequired: bool,
    rules: dict[str, Any],
) -> list[ContractQuestion]:
    """ERP 상태에 맞는 Contract Agent 질문을 생성한다."""

    if not contractReviewRequired:
        return []

    if request.materialContext is None:
        return []

    context = request.materialContext

    questionRules = rules[
        "contractQuestionRules"
    ]

    questionCodes: list[str] = []

    if facts.selectedPurchaseOrderStatus:
        statusValue = (
            facts.selectedPurchaseOrderStatus.value
        )

        questionCodes.extend(
            questionRules.get(
                statusValue,
                [],
            )
        )

    if context.alternativeSupplierStatus:
        alternativeValue = (
            context.alternativeSupplierStatus.value
        )

        questionCodes.extend(
            questionRules.get(
                alternativeValue,
                [],
            )
        )

    # 계약 검토가 필요한데 상태별 질문이 없으면
    # 기본 질문을 생성한다.
    if not questionCodes:
        questionCodes.extend(
            [
                "DELIVERY_DELAY_NOTICE",
                "FORCE_MAJEURE",
            ]
        )

    uniqueQuestionCodes = uniqueValues(
        questionCodes
    )

    contractId = (
        request.primaryContractId
        or context.primaryContractId
    )

    questions: list[ContractQuestion] = []

    for questionCodeValue in uniqueQuestionCodes:
        questionCode = ContractQuestionCode(
            questionCodeValue
        )

        questions.append(
            ContractQuestion(
                questionCode=questionCode,
                contractId=contractId,
                question=QUESTION_TEXTS[
                    questionCode
                ],
            )
        )

    return questions

def buildCalculationEvidence(
    request: ErpExposureRequest,
    facts: ErpExposureFacts,
    riskComponents: ErpRiskComponents,
    erpExposureScore: float | None,
) -> list[CalculationEvidence]:
    """Reviewer Agent가 사용할 계산 근거를 생성한다."""

    if request.materialContext is None:
        return []

    context = request.materialContext

    evidence: list[CalculationEvidence] = [
        CalculationEvidence(
            metric="availableQuantity",
            value=facts.availableQuantity,
            formula=(
                "onHandQuantity"
                " - reservedQuantity"
                " - blockedQuantity"
                " - qualityHoldQuantity"
            ),
            inputs={
                "onHandQuantity": (
                    context.onHandQuantity
                ),
                "reservedQuantity": (
                    context.reservedQuantity
                ),
                "blockedQuantity": (
                    context.blockedQuantity
                ),
                "qualityHoldQuantity": (
                    context.qualityHoldQuantity
                ),
            },
            sourceRecordIds=[
                context.materialId,
            ],
        ),
        CalculationEvidence(
            metric="inventoryDays",
            value=facts.inventoryDays,
            formula=(
                "availableQuantity"
                " / averageDailyUsage"
            ),
            inputs={
                "availableQuantity": (
                    facts.availableQuantity
                ),
                "averageDailyUsage": (
                    context.averageDailyUsage
                ),
            },
            sourceRecordIds=[
                context.materialId,
            ],
        ),
        CalculationEvidence(
            metric="expectedSupplyGapDays",
            value=(
                facts.expectedSupplyGapDays
            ),
            formula=(
                "max(0, nextEtaDays - inventoryDays)"
            ),
            inputs={
                "nextEtaDays": facts.nextEtaDays,
                "inventoryDays": facts.inventoryDays,
            },
            sourceRecordIds=uniqueValues(
                [
                    facts.selectedPurchaseOrderId,
                    facts.selectedPurchaseOrderItemId,
                ]
            ),
        ),
        CalculationEvidence(
            metric="erpExposureScore",
            value=erpExposureScore,
            formula=(
                "gapRiskScore*0.35"
                " + safetyStockRiskScore*0.20"
                " + dependencyRiskScore*0.20"
                " + purchaseOrderDelayRiskScore*0.15"
                " + alternativeSupplierRiskScore*0.10"
            ),
            inputs={
                "gapRiskScore": (
                    riskComponents.gapRiskScore
                ),
                "safetyStockRiskScore": (
                    riskComponents
                    .safetyStockRiskScore
                ),
                "dependencyRiskScore": (
                    riskComponents
                    .dependencyRiskScore
                ),
                "purchaseOrderDelayRiskScore": (
                    riskComponents
                    .purchaseOrderDelayRiskScore
                ),
                "alternativeSupplierRiskScore": (
                    riskComponents
                    .alternativeSupplierRiskScore
                ),
            },
            sourceRecordIds=[],
        ),
    ]

    return evidence

def calculateErpExposure(
    request: ErpExposureRequest,
) -> ErpExposureResponse:
    """
    ERP 사실 계산부터 최종 노출등급까지 수행한다.

    ERP Exposure Agent가 사용할 핵심 Service 함수다.
    """

    request = adaptErpExposureRequest(
        request
    )

    if not request.erpAnalysisRequired:
        raise ValueError(
            "ERP 분석이 필요하지 않은 요청입니다."
        )

    if request.materialContext is None:
        raise ValueError(
            "ERP 분석에 materialContext가 필요합니다."
        )

    rules = loadErpRules()

    supplierAssessments = (
        buildSupplierAssessments(
            suppliers=(
                request.alternativeSuppliers
            ),
            requiredQuantity=(
                request.requiredQuantity
            ),
            rules=rules,
        )
    )

    facts = calculateErpFacts(request)

    dataQualityStatus, warnings = (
        evaluateDataQuality(
            request=request,
            facts=facts,
            rules=rules,
        )
    )

    riskComponents = (
        calculateErpRiskComponents(
            request=request,
            facts=facts,
            rules=rules,
        )
    )

    manualReviewRequired = (
        dataQualityStatus
        is not DataQualityStatus.VALID
    )

    if manualReviewRequired:
        erpExposureScore = None
        forcedCritical = False
        forcedWarning = False
        exposureLevel = ExposureLevel.UNKNOWN

    else:
        erpExposureScore = (
            calculateErpExposureScore(
                riskComponents=riskComponents,
                rules=rules,
            )
        )

        forcedCritical = (
            determineForcedCritical(
                request=request,
                facts=facts,
                rules=rules,
            )
        )

        forcedWarning = (
            determineForcedWarning(
                request=request,
                facts=facts,
                rules=rules,
            )
        )

        exposureLevel = (
            determineExposureLevel(
                erpExposureScore=(
                    erpExposureScore
                ),
                forcedCritical=forcedCritical,
                forcedWarning=forcedWarning,
                rules=rules,
            )
        )

    contractReviewRequired = (
        False
        if manualReviewRequired
        else determineContractReviewRequired(
            request=request,
            facts=facts,
            exposureLevel=exposureLevel,
            rules=rules,
        )
    )

    questions = buildContractQuestions(
        request=request,
        facts=facts,
        contractReviewRequired=(
            contractReviewRequired
        ),
        rules=rules,
    )

    context = request.materialContext

    affectedSupplierIds = uniqueValues(
        [
            request.affectedSupplierId,
            context.primarySupplierId,
        ]
    )

    affectedContractIds = uniqueValues(
        [
            request.primaryContractId,
            context.primaryContractId,
        ]
    )

    if (
        contractReviewRequired
        and not affectedContractIds
    ):
        warnings.append(
            "계약 검토가 필요하지만 "
            "관련 contractId가 없습니다."
        )
        manualReviewRequired = True

    evidence = buildCalculationEvidence(
        request=request,
        facts=facts,
        riskComponents=riskComponents,
        erpExposureScore=erpExposureScore,
    )

    return ErpExposureResponse(
        requestId=request.requestId,
        affectedMaterialIds=[
            context.materialId,
        ],
        affectedSupplierIds=(
            affectedSupplierIds
        ),
        affectedContractIds=(
            affectedContractIds
        ),
        facts=facts,
        riskComponents=riskComponents,
        erpExposureScore=erpExposureScore,
        supplierAssessments=(
            supplierAssessments
        ),
        exposureLevel=exposureLevel,
        forcedCritical=forcedCritical,
        contractReviewRequired=(
            contractReviewRequired
        ),
        questionsForContractAgent=questions,
        calculationEvidence=evidence,
        dataQualityStatus=(
            dataQualityStatus
        ),
        manualReviewRequired=(
            manualReviewRequired
        ),
        warnings=warnings,
        ruleVersion=rules["ruleVersion"],
    )