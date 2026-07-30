import pytest

from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpAlternativeSupplierContext,
)

from app.services.erp_exposure_service import (
    calculateErpExposure,
)

from tests.test_erp_calculator import (
    createCobaltRequest,
)


def createAlternativeSupplier(
    availableCapacityQuantity: (
        float | None
    ),
    qualificationStatus: str,
) -> ErpAlternativeSupplierContext:
    """Service 통합 테스트용 대체 공급사."""

    return ErpAlternativeSupplierContext(
        supplierId="SUP-COD-02",
        contractId="CTR-011",
        supplierStatus="ACTIVE",
        availableCapacityQuantity=(
            availableCapacityQuantity
        ),
        leadTimeDays=25,
        qualificationStatus=(
            qualificationStatus
        ),
    )


def testApprovedAlternativeAffectsScore() -> None:
    """
    승인됐고 공급능력이 있는 대체 공급사는
    대체 공급사 위험점수를 0점으로 만든다.
    """

    originalRequest = (
        createCobaltRequest()
    )

    approvedSupplier = (
        createAlternativeSupplier(
            availableCapacityQuantity=7000,
            qualificationStatus="APPROVED",
        )
    )

    requestWithAlternatives = (
        originalRequest.model_copy(
            update={
                "alternativeSuppliers": [
                    approvedSupplier
                ]
            }
        )
    )

    result = calculateErpExposure(
        requestWithAlternatives
    )

    assert (
        result.riskComponents
        .alternativeSupplierRiskScore
        == 0
    )

    # 나머지 네 위험요소는 모두 100점이다.
    #
    # 100*0.35
    # + 100*0.20
    # + 100*0.20
    # + 100*0.15
    # + 0*0.10
    # = 90
    assert (
        result.erpExposureScore
        == 90
    )

    assert (
        result.contractReviewRequired
        is True
    )

    # Adapter는 입력 객체를 직접 변경하면 안 된다.
    assert (
        originalRequest.materialContext
        is not None
    )

    assert (
        originalRequest.materialContext
        .alternativeSupplierStatus
        is AlternativeSupplierStatus.NONE
    )


def testUnknownCapacityBecomesConditional() -> None:
    """
    승인은 됐지만 공급 가능 수량을 모르면
    CONDITIONAL 위험점수 60점을 적용한다.
    """

    originalRequest = (
        createCobaltRequest()
    )

    unverifiedSupplier = (
        createAlternativeSupplier(
            availableCapacityQuantity=None,
            qualificationStatus="APPROVED",
        )
    )

    requestWithAlternatives = (
        originalRequest.model_copy(
            update={
                "alternativeSuppliers": [
                    unverifiedSupplier
                ]
            }
        )
    )

    result = calculateErpExposure(
        requestWithAlternatives
    )

    assert (
        result.riskComponents
        .alternativeSupplierRiskScore
        == 60
    )

    # 35 + 20 + 20 + 15 + 6
    assert (
        result.erpExposureScore
        == 96
    )


def testDuplicateAlternativesRejectedByService() -> None:
    """중복 대체 공급사는 Service에서도 차단해야 한다."""

    originalRequest = (
        createCobaltRequest()
    )

    supplier = (
        createAlternativeSupplier(
            availableCapacityQuantity=7000,
            qualificationStatus="APPROVED",
        )
    )

    duplicatedRequest = (
        originalRequest.model_copy(
            update={
                "alternativeSuppliers": [
                    supplier,
                    supplier,
                ]
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="중복 supplierId",
    ):
        calculateErpExposure(
            duplicatedRequest
        )