from functools import lru_cache

from app.repositories.erp_repository import InMemoryErpRepository
from app.repositories.risk_repository import InMemoryRiskRepository
from app.services.briefing_service import BriefingService
from app.services.classification_service import ClassificationService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
from app.services.rag_service import RagService
from app.services.severity_service import SeverityService
from app.services.document_service import DocumentService


@lru_cache
def get_extraction_service() -> ExtractionService: return ExtractionService()


@lru_cache
def get_feature_service() -> FeatureService: return FeatureService()


@lru_cache
def get_classification_service() -> ClassificationService: return ClassificationService()


@lru_cache
def get_severity_service() -> SeverityService: return SeverityService()


@lru_cache
def get_erp_context_service() -> ErpContextService: return ErpContextService(InMemoryErpRepository())


@lru_cache
def get_briefing_service() -> BriefingService: return BriefingService()


@lru_cache
def get_risk_repository() -> InMemoryRiskRepository: return InMemoryRiskRepository()


@lru_cache
def get_rag_service() -> RagService: return RagService()


@lru_cache
def get_document_service() -> DocumentService: return DocumentService()


@lru_cache
def get_orchestration_service():
    from app.services.orchestration_service import OrchestrationService
    return OrchestrationService(
        extraction=get_extraction_service(), feature=get_feature_service(),
        classification=get_classification_service(), severity=get_severity_service(),
        erp=get_erp_context_service(), briefing=get_briefing_service(),
        rag=get_rag_service(), risk_repository=get_risk_repository(),
    )


def reset_dependencies() -> None:
    for dependency in (
        get_extraction_service, get_feature_service, get_classification_service,
        get_severity_service, get_erp_context_service, get_briefing_service,
        get_risk_repository, get_rag_service, get_document_service, get_orchestration_service,
    ):
        dependency.cache_clear()
