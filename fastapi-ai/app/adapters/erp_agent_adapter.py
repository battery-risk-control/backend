from __future__ import annotations

from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpAlternativeSupplierContext,
    ErpExposureRequest,
    SupplierQualificationStatus,
    SupplierStatus,
)


def hasPositiveCapacity(
    supplier: (
        ErpAlternativeSupplierContext
    ),
) -> bool:
    """공급 가능 수량이 확인됐고 0보다 큰지 검사한다."""

    return (
        supplier.availableCapacityQuantity
        is not None
        and supplier.availableCapacityQuantity
        > 0
    )


def hasPotentialCapacity(
    supplier: (
        ErpAlternativeSupplierContext
    ),
) -> bool:
    """
    공급 가능성이 남아 있는지 검사한다.

    None:
        공급 가능 수량을 아직 확인하지 못함

    0:
        현재 추가 공급할 수 없음
    """

    return (
        supplier.availableCapacityQuantity
        is None
        or supplier.availableCapacityQuantity
        > 0
    )


def validateAlternativeSuppliers(
    request: ErpExposureRequest,
) -> None:
    """대체 공급사 목록의 관계 오류를 검사한다."""

    alternativeSupplierIds = [
        supplier.supplierId
        for supplier
        in request.alternativeSuppliers
    ]

    if len(alternativeSupplierIds) != len(
        set(alternativeSupplierIds)
    ):
        raise ValueError(
            "alternativeSuppliers에 "
            "중복 supplierId가 있습니다."
        )

    if request.materialContext is None:
        return

    primarySupplierId = (
        request.materialContext
        .primarySupplierId
    )

    if (
        primarySupplierId is not None
        and primarySupplierId
        in alternativeSupplierIds
    ):
        raise ValueError(
            "주 공급사는 대체 공급사 목록에 "
            "포함될 수 없습니다."
        )


def deriveAlternativeSupplierStatus(
    alternativeSuppliers: list[
        ErpAlternativeSupplierContext
    ],
) -> AlternativeSupplierStatus:
    """
    대체 공급사 원본 목록을
    ERP 위험 계산용 요약 상태로 변환한다.
    """

    if not alternativeSuppliers:
        return AlternativeSupplierStatus.NONE

    # -----------------------------------------------------
    # 1. 즉시 사용 가능한 승인 공급사
    # -----------------------------------------------------

    approvedSuppliers = [
        supplier
        for supplier
        in alternativeSuppliers
        if (
            supplier.supplierStatus
            is SupplierStatus.ACTIVE
            and supplier.qualificationStatus
            is SupplierQualificationStatus
            .APPROVED
            and hasPositiveCapacity(
                supplier
            )
        )
    ]

    if approvedSuppliers:
        return (
            AlternativeSupplierStatus
            .APPROVED
        )

    # -----------------------------------------------------
    # 2. 조건 확인이 필요한 공급사
    # -----------------------------------------------------

    conditionalSuppliers = [
        supplier
        for supplier
        in alternativeSuppliers
        if (
            supplier.supplierStatus
            in {
                SupplierStatus.ACTIVE,
                SupplierStatus.UNDER_REVIEW,
            }
            and hasPotentialCapacity(
                supplier
            )
            and (
                supplier.qualificationStatus
                is SupplierQualificationStatus
                .CONDITIONAL
                or (
                    supplier
                    .qualificationStatus
                    is SupplierQualificationStatus
                    .APPROVED
                    and (
                        supplier.supplierStatus
                        is SupplierStatus
                        .UNDER_REVIEW
                        or supplier
                        .availableCapacityQuantity
                        is None
                    )
                )
            )
        )
    ]

    if conditionalSuppliers:
        return (
            AlternativeSupplierStatus
            .CONDITIONAL
        )

    # -----------------------------------------------------
    # 3. 승인 대기 공급사
    # -----------------------------------------------------

    pendingSuppliers = [
        supplier
        for supplier
        in alternativeSuppliers
        if (
            supplier.supplierStatus
            in {
                SupplierStatus.ACTIVE,
                SupplierStatus.UNDER_REVIEW,
            }
            and supplier.qualificationStatus
            is SupplierQualificationStatus
            .PENDING
            and hasPotentialCapacity(
                supplier
            )
        )
    ]

    if pendingSuppliers:
        return (
            AlternativeSupplierStatus
            .PENDING
        )

    # 전부 거래 중지, 승인 거절 또는 공급능력 0
    return AlternativeSupplierStatus.NONE


def adaptErpExposureRequest(
    request: ErpExposureRequest,
) -> ErpExposureRequest:
    """
    Spring 원본 요청을 ERP Calculator가 사용할
    내부 요청 형태로 변환한다.

    입력 객체를 직접 변경하지 않고 복사본을 반환한다.
    """

    if request.materialContext is None:
        return request

    # 기존 CSV 테스트와의 호환:
    # 목록이 없고 기존 요약 상태가 있으면 그 값을 유지한다.
    if (
        not request.alternativeSuppliers
        and request.materialContext
        .alternativeSupplierStatus
        is not None
    ):
        return request

    validateAlternativeSuppliers(
        request
    )

    derivedStatus = (
        deriveAlternativeSupplierStatus(
            request.alternativeSuppliers
        )
    )

    adaptedMaterialContext = (
        request.materialContext.model_copy(
            update={
                "alternativeSupplierStatus": (
                    derivedStatus
                )
            }
        )
    )

    return request.model_copy(
        update={
            "materialContext": (
                adaptedMaterialContext
            )
        }
    )