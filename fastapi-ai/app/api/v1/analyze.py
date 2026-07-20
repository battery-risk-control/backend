from fastapi import APIRouter, Depends

from app.api.dependencies import get_orchestration_service
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponseData
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.services.orchestration_service import OrchestrationService

router = APIRouter(prefix="/api/v1", tags=["analyze"])


@router.post(
    "/analyze", response_model=ApiResponse[AnalyzeResponseData],
    responses={422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}, 503: {"model": ApiErrorResponse}},
)
def analyze_event(
    request: AnalyzeRequest,
    service: OrchestrationService = Depends(get_orchestration_service),
) -> ApiResponse[AnalyzeResponseData]:
    return ApiResponse(data=service.analyze(request))
