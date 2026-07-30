from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from app.api.dependencies import (
    get_briefing_service, get_extraction_service, get_severity_service,
)
from app.schemas.analyze import EventInput
from app.schemas.briefing import (
    BriefingComposeRequest,
    BriefingComposeResult,
    BriefingGenerationRequest,
    BriefingGenerationResult,
)
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.extraction import ExtractionRequest, ExtractionResult
from app.schemas.internal import SeverityScoreRequest
from app.schemas.severity import SeverityResult
from app.services.briefing_service import BriefingService
from app.services.extraction_service import ExtractionService
from app.services.severity_service import SeverityService

router = APIRouter(prefix="/api/v1/internal", tags=["internal"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}}


@router.post("/llm/extract", response_model=ApiResponse[ExtractionResult], responses=ERRORS)
def extract(request: ExtractionRequest, service: ExtractionService = Depends(get_extraction_service)) -> ApiResponse[ExtractionResult]:
    event = EventInput(
        external_event_id="INTERNAL-MOCK", title=request.title, content=request.content,
        source_name="INTERNAL", published_at=datetime.now(timezone.utc), country_code=request.country_code,
    )
    return ApiResponse(data=service.extract(event))


@router.post("/severity/score", response_model=ApiResponse[SeverityResult], responses=ERRORS)
def score(
    request: SeverityScoreRequest,
    service: SeverityService = Depends(get_severity_service),
) -> ApiResponse[SeverityResult]:
    return ApiResponse(data=service.evaluate(request))


@router.post("/briefings", response_model=ApiResponse[BriefingGenerationResult], responses=ERRORS)
def create_briefing(
    request: BriefingGenerationRequest,
    service: BriefingService = Depends(get_briefing_service),
) -> ApiResponse[BriefingGenerationResult]:
    return ApiResponse(data=service.generate(request))


@router.post("/briefings/compose", response_model=ApiResponse[BriefingComposeResult], responses=ERRORS)
def compose_briefing(
    request: BriefingComposeRequest,
    service: BriefingService = Depends(get_briefing_service),
) -> ApiResponse[BriefingComposeResult]:
    """13단계: Spring이 모은 ERP·Severity·RAG·대체공급사 값을 템플릿 브리핑으로 조립한다."""
    return ApiResponse(data=service.compose(request))
