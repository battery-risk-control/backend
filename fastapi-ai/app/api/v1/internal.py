from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from app.api.dependencies import (
    get_briefing_service, get_classification_service, get_extraction_service,
    get_feature_service, get_severity_service,
)
from app.repositories.erp_repository import ErpContext
from app.schemas.analyze import EventInput
from app.schemas.briefing import BriefingGenerationRequest, BriefingGenerationResult
from app.schemas.classification import ClassificationResult
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.extraction import ExtractionRequest, ExtractionResult
from app.schemas.internal import MlClassifyRequest, SeverityScoreRequest
from app.schemas.severity import SeverityResult
from app.services.briefing_service import BriefingService
from app.services.classification_service import ClassificationService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
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


@router.post("/ml/classify", response_model=ApiResponse[ClassificationResult], responses=ERRORS)
def classify(
    request: MlClassifyRequest,
    service: ClassificationService = Depends(get_classification_service),
    feature_service: FeatureService = Depends(get_feature_service),
) -> ApiResponse[ClassificationResult]:
    return ApiResponse(data=service.classify(request.features or feature_service.build(None)))


@router.post("/severity/score", response_model=ApiResponse[SeverityResult], responses=ERRORS)
def score(
    request: SeverityScoreRequest,
    service: SeverityService = Depends(get_severity_service),
    feature_service: FeatureService = Depends(get_feature_service),
) -> ApiResponse[SeverityResult]:
    context = None
    if request.stock_days is not None:
        context = ErpContext(1, [11], [501], request.stock_days, 20, "2026-08-04")
    return ApiResponse(data=service.score(request.features or feature_service.build(None), context))


@router.post("/briefings", response_model=ApiResponse[BriefingGenerationResult], responses=ERRORS)
def create_briefing(
    request: BriefingGenerationRequest,
    service: BriefingService = Depends(get_briefing_service),
) -> ApiResponse[BriefingGenerationResult]:
    return ApiResponse(data=service.generate(request))
