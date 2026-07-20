from datetime import datetime, timezone

from app.core.config import get_settings
from app.repositories.erp_repository import ErpContext
from app.repositories.risk_repository import RiskRepository
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponseData, MatchedEntities
from app.schemas.briefing import BriefingGenerationRequest
from app.schemas.common import ProcessingStatus
from app.services.briefing_service import BriefingService
from app.services.classification_service import ClassificationService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
from app.services.rag_service import RagService
from app.services.severity_service import SeverityService


class OrchestrationService:
    def __init__(self, extraction: ExtractionService, feature: FeatureService,
                 classification: ClassificationService, severity: SeverityService,
                 erp: ErpContextService, briefing: BriefingService, rag: RagService,
                 risk_repository: RiskRepository) -> None:
        self.extraction = extraction
        self.feature = feature
        self.classification = classification
        self.severity = severity
        self.erp = erp
        self.briefing = briefing
        self.rag = rag
        self.risk_repository = risk_repository

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponseData:
        extraction = self.extraction.extract(request.event)
        features = self.feature.build(request.feature_overrides, request.options.enrich_features)
        classification = self.classification.classify(features)
        erp_context = self.erp.get_context(extraction) if request.options.include_erp_context else None
        severity = self.severity.score(features, erp_context)

        entities = self.erp.matched_entities(erp_context) if erp_context else MatchedEntities(
            material_id=0, supplier_ids=[], contract_ids=[]
        )
        if request.options.include_contract_rag:
            contract_id = entities.contract_ids[0] if entities.contract_ids else 501
            self.rag.search(request.event.title, contract_id, None, 5)

        briefing_id = None
        if request.options.generate_briefing:
            briefing_id = self.briefing.generate(BriefingGenerationRequest(
                risk_id=101,
                event_summary=extraction.summary_kr,
                inventory_summary=(f"재고 {erp_context.stock_days}일" if erp_context else "ERP Context 제외"),
                contract_summary=("계약 RAG 검색 포함" if request.options.include_contract_rag else "계약 RAG 제외"),
            )).briefing_id

        result = AnalyzeResponseData(
            analysis_id="ANL-20260720-0001", status=ProcessingStatus.COMPLETED,
            extraction=extraction, features=features, classification=classification,
            severity=severity, matched_entities=entities, briefing_id=briefing_id,
            erp_context_included=request.options.include_erp_context,
            contract_rag_included=request.options.include_contract_rag,
            feature_enrichment_applied=request.options.enrich_features,
            mock=get_settings().mock_mode,
            mock_reason="LLM, XGBoost, ERP and ChromaDB adapters are not connected",
            processed_at=datetime.now(timezone.utc),
        )
        self.risk_repository.save_analysis(result)
        return result


def analyze(request: AnalyzeRequest) -> AnalyzeResponseData:
    from app.api.dependencies import get_orchestration_service
    return get_orchestration_service().analyze(request)
