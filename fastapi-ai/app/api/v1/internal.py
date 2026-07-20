from fastapi import APIRouter
from pydantic import Field

from app.schemas.analyze import EventInput, FeatureVector
from app.schemas.common import ApiResponse
from app.schemas.internal import (
    BriefingGenerateRequest as BriefingRequest,
    LlmExtractRequest as ExtractionRequest,
    MlClassifyRequest as ClassificationRequest,
    SeverityScoreRequest as SeverityRequest,
)
from app.services.classification_service import ClassificationService
from app.services.extraction_service import ExtractionService
from app.services.severity_service import SeverityService
from app.services.feature_service import FeatureService
from app.repositories.erp_repository import ErpContext

router = APIRouter(prefix="/api/v1/internal", tags=["internal"])
extraction_service = ExtractionService()
classification_service = ClassificationService()
severity_service = SeverityService()
feature_service = FeatureService()


@router.post("/llm/extract", response_model=ApiResponse[dict])
def extract(request: ExtractionRequest) -> ApiResponse[dict]:
    from datetime import datetime, timezone
    result = extraction_service.extract(EventInput(
        external_event_id="INTERNAL-MOCK", title=request.title, content=request.content,
        source_name="INTERNAL", published_at=datetime.now(timezone.utc), country_code=request.country_code,
    ))
    return ApiResponse(data={**result.model_dump(mode="json", by_alias=True), "mock": True})


@router.post("/ml/classify", response_model=ApiResponse[dict])
def classify(_request: ClassificationRequest) -> ApiResponse[dict]:
    result = classification_service.classify(_request.features or feature_service.build(None))
    return ApiResponse(data={**result.model_dump(mode="json", by_alias=True), "mock": True})


@router.post("/severity/score", response_model=ApiResponse[dict])
def score(_request: SeverityRequest) -> ApiResponse[dict]:
    features = _request.features or feature_service.build(None)
    erp_context = None
    if _request.stock_days is not None:
        erp_context = ErpContext(
            material_id=1, supplier_ids=[11], contract_ids=[501],
            stock_days=_request.stock_days, safety_stock_days=20,
            expected_inbound_date="2026-08-04",
        )
    result = severity_service.score(features, erp_context)
    return ApiResponse(data={**result.model_dump(mode="json", by_alias=True), "mock": True})


@router.post("/briefings", response_model=ApiResponse[dict])
def create_briefing(request: BriefingRequest) -> ApiResponse[dict]:
    return ApiResponse(data={"briefingId": 7001, "riskId": request.risk_id, "status": "COMPLETED", "mock": True})
