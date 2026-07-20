from datetime import datetime, timezone

from app.core.config import get_settings
from app.repositories.risk_repository import InMemoryRiskRepository
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponseData
from app.schemas.common import ProcessingStatus
from app.services.briefing_service import BriefingService
from app.services.classification_service import ClassificationService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
from app.services.severity_service import SeverityService

extraction_service = ExtractionService()
feature_service = FeatureService()
classification_service = ClassificationService()
erp_context_service = ErpContextService()
severity_service = SeverityService()
briefing_service = BriefingService()
risk_repository = InMemoryRiskRepository()


def analyze(request: AnalyzeRequest) -> AnalyzeResponseData:
    extraction = extraction_service.extract(request.event)
    features = feature_service.build(request.feature_overrides)
    classification = classification_service.classify(features)
    erp_context = erp_context_service.get_context(extraction) if request.options.include_erp_context else None
    severity = severity_service.score(features, erp_context)

    if erp_context is None:
        from app.repositories.erp_repository import ErpContext
        erp_context = ErpContext(1, [], [], 0, 0, "")

    result = AnalyzeResponseData(
        analysis_id="ANL-20260720-0001",
        status=ProcessingStatus.COMPLETED,
        extraction=extraction,
        features=features,
        classification=classification,
        severity=severity,
        matched_entities=erp_context_service.matched_entities(erp_context),
        briefing_id=briefing_service.generate(erp_context) if request.options.generate_briefing else None,
        mock=get_settings().mock_mode,
        mock_reason="LLM, XGBoost, ERP and RAG adapters are not connected",
        processed_at=datetime.now(timezone.utc),
    )
    risk_repository.save_analysis(result)
    return result
