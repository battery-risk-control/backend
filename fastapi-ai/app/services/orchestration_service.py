from datetime import datetime, timezone
from uuid import uuid4

from app.core.config import get_settings
from app.repositories.risk_repository import RiskRepository
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponseData, MatchedEntities
from app.schemas.briefing import BriefingGenerationRequest
from app.schemas.classification import ClassificationResult
from app.schemas.common import ImpactDomain, ProcessingStatus
from app.services.briefing_service import BriefingService
from app.services.classification_service import ClassificationService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
from app.services.rag_service import RagService
from app.services.severity_service import SeverityService


class OrchestrationService:
    """F3 분석 파이프라인 (surin ↔ merge 병합).

    - 골격/선택 스테이지/rich 응답: merge — erp_context·rag·briefing·matched_entities·save_analysis 유지.
    - impact_domain: surin — LLM 추출 draft를 그대로 사용(도메인 전용 XGBoost 없음).
      merge XGBoost classifier(classification_service)는 삭제하지 않고 /ml/classify(internal)로 보존.
    - severity: surin — severity_service.score(features) → severity_engine(severity-rule-v0.2-realtime, 4,081건 검증).
    - 추출 tone/relevance 주입 + risk_repository.remember 중복방지: surin.
    """

    def __init__(self, extraction: ExtractionService, feature: FeatureService,
                 classification: ClassificationService, severity: SeverityService,
                 erp: ErpContextService, briefing: BriefingService, rag: RagService,
                 risk_repository: RiskRepository) -> None:
        self.extraction = extraction
        self.feature = feature
        self.classification = classification  # /analyze 경로 미사용, /ml/classify로 보존
        self.severity = severity
        self.erp = erp
        self.briefing = briefing
        self.rag = rag
        self.risk_repository = risk_repository

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponseData:
        self.risk_repository.remember(request.event.external_event_id)

        extraction = self.extraction.extract(request.event)
        features = self.feature.build(request.feature_overrides, request.options.enrich_features)
        # 추출이 판단한 톤·공급망 관련성을 심각도 입력에 반영(override 명시 시 존중).
        feature_updates = {}
        if request.feature_overrides is None or request.feature_overrides.tone_score is None:
            feature_updates["tone_score"] = extraction.tone_score
        if request.feature_overrides is None or request.feature_overrides.is_supply_chain_relevant is None:
            feature_updates["is_supply_chain_relevant"] = extraction.is_supply_chain_relevant
        if feature_updates:
            features = features.model_copy(update=feature_updates)

        # impact_domain: LLM 추출 결과 직결(surin 설계). XGBoost classifier는 /ml/classify로 보존.
        classification = ClassificationResult(
            impact_domain=ImpactDomain(extraction.impact_domain_draft),
            confidence=1.0,
            model_version=extraction.extraction_model_version,
            mock=extraction.mock,
        )

        erp_context = self.erp.get_context(extraction) if request.options.include_erp_context else None
        # severity: severity_engine(severity-rule-v0.2-realtime, 검증 공식) 얇은 위임 호출(surin).
        severity = self.severity.score(features)

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
            analysis_id=str(uuid4()), status=ProcessingStatus.COMPLETED,
            extraction=extraction, features=features, classification=classification,
            severity=severity, matched_entities=entities,
            affected_materials=extraction.affected_materials, briefing_id=briefing_id,
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
