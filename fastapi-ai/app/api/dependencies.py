import logging
from functools import lru_cache

from app.core.config import get_settings
from app.repositories.erp_repository import InMemoryErpRepository
from app.repositories.risk_repository import InMemoryRiskRepository
from app.services.briefing_service import BriefingService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.feature_service import FeatureService
from app.services.rag_service import RagService
from app.services.severity_service import SeverityService
from app.services.document_service import DocumentService
from app.services.supplier_recommendation_service import SupplierRecommendationService
from app.services.embedding_service import (
    EmbeddingProvider,
    get_embedding_provider as build_embedding_provider,
)
from app.services.vector_store_service import ChromaVectorStore
from app.multi_agent.graph.briefing_graph import (
    build_briefing_graph,
)

logger = logging.getLogger(__name__)


@lru_cache
def get_extraction_service() -> ExtractionService:
    """[surin F3] OPENAI_API_KEY가 설정돼 있으면 실제 GPT-4o-mini 추출, 아니면 mock 폴백."""
    import os
    if os.environ.get("OPENAI_API_KEY"):
        try:
            from app.models.extraction_inference import OpenAIExtractionInference
            service = ExtractionService(OpenAIExtractionInference())
            logger.warning("[EXTRACTION MODE] 실제 GPT-4o-mini 추출기로 초기화됨 (mock 아님)")
            return service
        except Exception as exception:
            logger.warning("[EXTRACTION MODE] OpenAI 추출기 초기화 실패, mock 추출기로 폴백합니다: %s", exception)
            return ExtractionService()
    logger.warning("[EXTRACTION MODE] OPENAI_API_KEY 미설정 — mock 추출기로 동작합니다")
    return ExtractionService()


@lru_cache
def get_summary_service():
    """경영기획 상세용 '자세한 요약' 생성기. OPENAI_API_KEY가 있으면 실제 LLM, 없으면 mock."""
    import os
    from app.services.summary_service import SummaryService
    use_openai = bool(os.environ.get("OPENAI_API_KEY"))
    try:
        service = SummaryService(use_openai=use_openai)
        logger.warning("[SUMMARY MODE] %s", "gpt-4o-mini 요약기" if use_openai else "mock 요약기")
        return service
    except Exception as exception:  # noqa: BLE001 - 초기화 실패 시 mock으로 폴백
        logger.warning("[SUMMARY MODE] OpenAI 요약기 초기화 실패, mock으로 폴백: %s", exception)
        return SummaryService(use_openai=False)


@lru_cache
def get_feature_service() -> FeatureService: return FeatureService()


@lru_cache
def get_severity_service() -> SeverityService: return SeverityService()


@lru_cache
def get_erp_context_service() -> ErpContextService: return ErpContextService(InMemoryErpRepository())


@lru_cache
def get_briefing_service() -> BriefingService: return BriefingService()


@lru_cache
def get_risk_repository() -> InMemoryRiskRepository: return InMemoryRiskRepository()


@lru_cache
def get_embedding_service() -> EmbeddingProvider:
    settings = get_settings()
    if settings.embedding_provider == "openai":
        from app.services.embedding_service import OpenAIEmbedding
        provider = OpenAIEmbedding(
            api_key=settings.openai_api_key,
            model=settings.openai_embedding_model,
            dimension=settings.embedding_dimension,
        )
        return build_embedding_provider(settings, openai_provider=provider)
    return build_embedding_provider(settings)


@lru_cache
def get_vector_store() -> ChromaVectorStore:
    return ChromaVectorStore(get_embedding_service())


@lru_cache
def get_rag_service() -> RagService: return RagService(vector_store=get_vector_store())

@lru_cache
def get_multi_agent_graph():
    """
    Minji의 실제 RagService가 연결된
    멀티에이전트 LangGraph를 반환한다.
    """

    return build_briefing_graph(
        get_rag_service(),
    )

@lru_cache
def get_document_service() -> DocumentService:
    return DocumentService(vector_store=get_vector_store())


@lru_cache
def get_supplier_recommendation_service() -> SupplierRecommendationService:
    return SupplierRecommendationService()


@lru_cache
def get_orchestration_service():
    from app.services.orchestration_service import OrchestrationService
    return OrchestrationService(
        extraction=get_extraction_service(), feature=get_feature_service(),
        severity=get_severity_service(),
        erp=get_erp_context_service(), briefing=get_briefing_service(),
        rag=get_rag_service(), risk_repository=get_risk_repository(),
    )


def reset_dependencies() -> None:
    for dependency in (
        get_extraction_service, get_summary_service, get_feature_service,
        get_severity_service, get_erp_context_service, get_briefing_service,
        get_risk_repository, get_embedding_service, get_vector_store,
        get_rag_service, get_document_service, get_multi_agent_graph,
        get_supplier_recommendation_service, get_orchestration_service,
    ):
        dependency.cache_clear()
