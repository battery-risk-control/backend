from fastapi import APIRouter

from app.schemas.analyze import AnalyzeRequest, AnalyzeResponseData
from app.schemas.common import ApiResponse
from app.services.orchestration_service import analyze

router = APIRouter(prefix="/api/v1", tags=["analyze"])


@router.post("/analyze", response_model=ApiResponse[AnalyzeResponseData])
def analyze_event(request: AnalyzeRequest) -> ApiResponse[AnalyzeResponseData]:
    return ApiResponse(data=analyze(request))
