from fastapi import APIRouter, HTTPException, status

from app.schemas.erp import (
    ErpExposureRequest,
    ErpExposureResponse,
)

from app.services.erp_exposure_service import (
    calculateErpExposure,
)


erpRouter = APIRouter(
    prefix="/api/v1/internal/erp",
    tags=["Internal ERP Analysis"],
)


@erpRouter.post(
    "/exposure",
    response_model=ErpExposureResponse,
    status_code=status.HTTP_200_OK,
    summary="ERP 자사 노출도 분석",
    description=(
        "Spring이 전달한 ERP Context를 바탕으로 "
        "가용재고, 재고일수, 공급 공백, "
        "ERP Exposure Score와 계약 검토 필요성을 "
        "계산합니다."
    ),
)

def analyzeErpExposure(
    request: ErpExposureRequest,
) -> ErpExposureResponse:
    """
    ERP Exposure Agent의 계산 기능을
    독립적으로 테스트하기 위한 내부 API.

    최종 멀티 에이전트 구현에서는 LangGraph의
    ERP Exposure Agent Node가 같은 Service 함수를
    직접 호출한다.
    """

    try:
        return calculateErpExposure(request)

    except ValueError as error:
        raise HTTPException(
            status_code=(
                status.HTTP_422_UNPROCESSABLE_ENTITY
            ),
            detail=str(error),
        ) from error