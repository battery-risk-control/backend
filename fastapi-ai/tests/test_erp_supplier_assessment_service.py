from app.schemas.erp import (
    ErpAlternativeSupplierContext,
)
from app.services.erp_rule_loader import (
    loadErpRules,
)
from app.services.erp_supplier_assessment_service import (
    evaluateSupplier,
)


def createSupplier(
    supplierId: str = "SUP-A",
    supplierStatus: str = "ACTIVE",
    qualificationStatus: str = "APPROVED",
    availableCapacityQuantity: (
        float | None
    ) = 10000,
) -> ErpAlternativeSupplierContext:
    return ErpAlternativeSupplierContext(
        supplierId=supplierId,
        contractId="CTR-011",
        supplierStatus=supplierStatus,
        qualificationStatus=(
            qualificationStatus
        ),
        availableCapacityQuantity=(
            availableCapacityQuantity
        ),
        leadTimeDays=10,
    )


def testSufficientApprovedSupplier() -> None:
    result = evaluateSupplier(
        supplier=createSupplier(),
        requiredQuantity=8000,
        rules=loadErpRules(),
    )

    assert result.supplierRiskScore == 0
    assert (
        result.capacityStatus.value
        == "SUFFICIENT"
    )
    assert (
        result.capacityCoverageRatio
        == 1.25
    )


def testConditionalPartialSupplier() -> None:
    result = evaluateSupplier(
        supplier=createSupplier(
            qualificationStatus="CONDITIONAL",
            availableCapacityQuantity=4000,
        ),
        requiredQuantity=8000,
        rules=loadErpRules(),
    )

    assert (
        result.qualificationRiskScore
        == 60
    )
    assert result.capacityRiskScore == 50

    # 60 × 0.4 + 50 × 0.3 = 39에서 수정.
    # capacity 가중치 0.0 (erp_rules.yaml 80행 주석: 가용 capacity 원천 데이터 없음)
    # 60 × 0.57 + 50 × 0.0 = 34.2
    assert result.supplierRiskScore == 34.2


def testUnknownCapacity() -> None:
    result = evaluateSupplier(
        supplier=createSupplier(
            availableCapacityQuantity=None,
        ),
        requiredQuantity=8000,
        rules=loadErpRules(),
    )

    assert result.capacityRiskScore == 70
    assert (
        result.manualReviewRequired
        is True
    )
    assert (
        "CAPACITY_UNKNOWN"
        in result.reasonCodes
    )


def testUnderReviewSupplier() -> None:
    result = evaluateSupplier(
        supplier=createSupplier(
            supplierStatus="UNDER_REVIEW",
        ),
        requiredQuantity=8000,
        rules=loadErpRules(),
    )

    assert (
        result.supplierStatusRiskScore
        == 60
    )

    # 60 × 0.3에서 수정
    # 60 × 0.43 = 25.8
    assert result.supplierRiskScore == 25.8