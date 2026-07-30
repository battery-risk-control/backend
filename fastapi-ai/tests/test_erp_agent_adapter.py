import pytest

from app.adapters.erp_agent_adapter import (
    adaptErpExposureRequest,
    deriveAlternativeSupplierStatus,
)

from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpAlternativeSupplierContext,
)

from tests.test_erp_calculator import (
    createCobaltRequest,
)


def createAlternativeSupplier(
    supplierId: str = "SUP-COD-02",
    supplierStatus: str = "ACTIVE",
    availableCapacityQuantity: (
        float | None
    ) = 7000,
    qualificationStatus: str = "APPROVED",
) -> ErpAlternativeSupplierContext:
    """Adapter 테스트용 대체 공급사를 생성한다."""

    return ErpAlternativeSupplierContext(
        supplierId=supplierId,
        contractId="CTR-011",
        supplierStatus=supplierStatus,
        availableCapacityQuantity=(
            availableCapacityQuantity
        ),
        leadTimeDays=25,
        qualificationStatus=(
            qualificationStatus
        ),
    )

def testApprovedAlternativeSupplier() -> None:
    """승인·활성·공급능력 보유 공급사는 APPROVED."""

    suppliers = [
        createAlternativeSupplier()
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus
        .APPROVED
    )

def testApprovedButCapacityUnknown() -> None:
    """승인 공급사라도 공급능력 미확인은 CONDITIONAL."""

    suppliers = [
        createAlternativeSupplier(
            availableCapacityQuantity=None,
        )
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus
        .CONDITIONAL
    )

def testConditionalAlternativeSupplier() -> None:
    """조건부 승인 공급사는 CONDITIONAL."""

    suppliers = [
        createAlternativeSupplier(
            qualificationStatus=(
                "CONDITIONAL"
            ),
        )
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus
        .CONDITIONAL
    )

def testPendingAlternativeSupplier() -> None:
    """승인 대기 공급사는 PENDING."""

    suppliers = [
        createAlternativeSupplier(
            qualificationStatus="PENDING",
        )
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus
        .PENDING
    )

def testUnavailableAlternativeSupplier() -> None:
    """공급능력이 0이면 대체 공급사로 사용할 수 없다."""

    suppliers = [
        createAlternativeSupplier(
            availableCapacityQuantity=0,
        )
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus.NONE
    )

def testSuspendedAlternativeSupplier() -> None:
    """거래 중지 공급사는 승인돼도 사용할 수 없다."""

    suppliers = [
        createAlternativeSupplier(
            supplierStatus="SUSPENDED",
        )
    ]

    result = (
        deriveAlternativeSupplierStatus(
            suppliers
        )
    )

    assert (
        result
        is AlternativeSupplierStatus.NONE
    )

def testAdapterOverridesLegacyStatus() -> None:
    """원본 목록이 있으면 기존 요약값보다 목록을 우선한다."""

    request = createCobaltRequest()

    approvedSupplier = (
        createAlternativeSupplier()
    )

    requestWithAlternatives = (
        request.model_copy(
            update={
                "alternativeSuppliers": [
                    approvedSupplier
                ]
            }
        )
    )

    adaptedRequest = (
        adaptErpExposureRequest(
            requestWithAlternatives
        )
    )

    assert (
        request.materialContext
        is not None
    )

    assert (
        request.materialContext
        .alternativeSupplierStatus
        is AlternativeSupplierStatus.NONE
    )

    assert (
        adaptedRequest.materialContext
        is not None
    )

    assert (
        adaptedRequest.materialContext
        .alternativeSupplierStatus
        is AlternativeSupplierStatus
        .APPROVED
    )

def testAdapterPreservesLegacyStatus() -> None:
    """목록이 없으면 기존 CSV 요약값을 유지한다."""

    request = createCobaltRequest()

    adaptedRequest = (
        adaptErpExposureRequest(
            request
        )
    )

    assert (
        adaptedRequest.materialContext
        is not None
    )

    assert (
        adaptedRequest.materialContext
        .alternativeSupplierStatus
        is AlternativeSupplierStatus.NONE
    )

def testDuplicateAlternativeSupplierIds() -> None:
    """동일 공급사가 두 번 들어오면 오류."""

    request = createCobaltRequest()

    firstSupplier = (
        createAlternativeSupplier()
    )

    secondSupplier = (
        createAlternativeSupplier()
    )

    duplicatedRequest = (
        request.model_copy(
            update={
                "alternativeSuppliers": [
                    firstSupplier,
                    secondSupplier,
                ]
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="중복 supplierId",
    ):
        adaptErpExposureRequest(
            duplicatedRequest
        )

def testPrimarySupplierCannotBeAlternative() -> None:
    """주 공급사가 대체 공급사 목록에 포함되면 오류."""

    request = createCobaltRequest()

    primarySupplier = (
        createAlternativeSupplier(
            supplierId="SUP-COD-01",
        )
    )

    invalidRequest = (
        request.model_copy(
            update={
                "alternativeSuppliers": [
                    primarySupplier
                ]
            }
        )
    )

    with pytest.raises(
        ValueError,
        match="주 공급사는 대체 공급사",
    ):
        adaptErpExposureRequest(
            invalidRequest
        )