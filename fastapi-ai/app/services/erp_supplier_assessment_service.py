from typing import Any

from app.schemas.erp import ErpAlternativeSupplierContext, ErpSupplierAssessment, SupplierCapacityStatus


def calculateCapacityRisk(
    availableCapacityQuantity: float | None,
    requiredQuantity: float | None,
    rules: dict[str, Any],
) -> tuple[float, SupplierCapacityStatus, float | None, list[str], bool]:
    capacityRules = rules["supplierAssessmentRisk"]["capacity"]
    thresholds = rules["supplierAssessmentRisk"]["coverageThresholds"]

    if availableCapacityQuantity is None:
        return float(capacityRules["unknown"]), SupplierCapacityStatus.UNKNOWN, None, ["CAPACITY_UNKNOWN"], True

    if availableCapacityQuantity == 0:
        return float(capacityRules["unavailable"]), SupplierCapacityStatus.UNAVAILABLE, 0.0, ["NO_AVAILABLE_CAPACITY"], False

    if requiredQuantity is None or requiredQuantity <= 0:
        return (
            float(capacityRules["requiredQuantityUnknown"]), SupplierCapacityStatus.UNKNOWN, None,
            ["REQUIRED_QUANTITY_UNKNOWN"], True,
        )

    coverageRatio = availableCapacityQuantity / requiredQuantity

    if coverageRatio >= thresholds["sufficient"]:
        return float(capacityRules["sufficient"]), SupplierCapacityStatus.SUFFICIENT, coverageRatio, [], False

    if coverageRatio >= thresholds["partial"]:
        return (
            float(capacityRules["partial"]), SupplierCapacityStatus.PARTIAL, coverageRatio,
            ["CAPACITY_PARTIALLY_SUFFICIENT"], False,
        )

    return (
        float(capacityRules["severelyInsufficient"]), SupplierCapacityStatus.PARTIAL, coverageRatio,
        ["CAPACITY_SEVERELY_INSUFFICIENT"], False,
    )


def evaluateSupplier(
    supplier: ErpAlternativeSupplierContext, requiredQuantity: float | None, rules: dict[str, Any],
) -> ErpSupplierAssessment:
    assessmentRules = rules["supplierAssessmentRisk"]

    qualificationRisk = float(assessmentRules["qualification"][supplier.qualificationStatus.value])
    supplierStatusRisk = float(assessmentRules["supplierStatus"][supplier.supplierStatus.value])

    capacityRisk, capacityStatus, coverageRatio, capacityReasonCodes, manualReviewRequired = calculateCapacityRisk(
        availableCapacityQuantity=supplier.availableCapacityQuantity,
        requiredQuantity=requiredQuantity,
        rules=rules,
    )

    weights = assessmentRules["weights"]
    supplierRiskScore = (
        qualificationRisk * weights["qualification"]
        + supplierStatusRisk * weights["supplierStatus"]
        + capacityRisk * weights["capacity"]
    )

    reasonCodes = list(capacityReasonCodes)
    if qualificationRisk > 0:
        reasonCodes.append("QUALIFICATION_" + supplier.qualificationStatus.value)
    if supplierStatusRisk > 0:
        reasonCodes.append("SUPPLIER_STATUS_" + supplier.supplierStatus.value)

    return ErpSupplierAssessment(
        supplierId=supplier.supplierId,
        supplierRiskScore=round(supplierRiskScore, 2),
        qualificationRiskScore=qualificationRisk,
        supplierStatusRiskScore=supplierStatusRisk,
        capacityRiskScore=capacityRisk,
        capacityStatus=capacityStatus,
        capacityCoverageRatio=None if coverageRatio is None else round(coverageRatio, 4),
        availableCapacityQuantity=supplier.availableCapacityQuantity,
        reasonCodes=reasonCodes,
        manualReviewRequired=manualReviewRequired,
    )


def buildSupplierAssessments(
    suppliers: list[ErpAlternativeSupplierContext], requiredQuantity: float | None, rules: dict[str, Any],
) -> list[ErpSupplierAssessment]:
    return [evaluateSupplier(supplier=supplier, requiredQuantity=requiredQuantity, rules=rules) for supplier in suppliers]
