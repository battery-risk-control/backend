from dataclasses import dataclass
from functools import lru_cache
import os


@dataclass(frozen=True)
class Settings:
    app_name: str = "Battery Raw Material Supply Chain Risk AI API"
    app_version: str = "0.1.0"
    environment: str = "local"
    mock_mode: bool = True
    severity_rule_version: str = "severity-rule-v1"
    chunk_size: int = 900
    chunk_overlap: int = 120
    embedding_provider: str = "mock"
    embedding_dimension: int = 1536
    chroma_mode: str = "persistent"
    chroma_host: str = "localhost"
    chroma_port: int = 8001
    chroma_ssl: bool = False
    chroma_persist_directory: str = "./data/chroma"
    chroma_collection_prefix: str = "contract_documents"
    openai_api_key: str = ""
    openai_embedding_model: str = "text-embedding-3-small"
    spring_base_url: str = "http://localhost:8080"  # [surin F9] SupplierRecommendationService의 Spring 조회 URL 조립용
    kg_service_base_url: str = "http://host.docker.internal:8100"  # kg_service(GET /resolve) 조회 URL 조립용
    # KG 게이트 on/off. 기본 true(운영 동작 그대로).
    #
    # false로 두면 kg_service 조회 결과와 무관하게 게이트를 통과시켜 ERP·계약 노드를 태운다.
    # kg_service가 떠 있지 않을 때 파이프라인 뒷단(ERP 노출도·계약 공백·종합 위험도·브리핑)을
    # 확인하려는 용도다 — 리졸버가 죽으면 resolve_kg_context가 "매칭 없음"으로 폴백하고,
    # 게이트가 거기서 전부 조기 종료시켜 erp_exposure_score가 NULL·NORMAL로만 쌓이기 때문이다.
    #
    # ⚠️ 우회한 실행은 "KG가 확정한 결과"가 아니다. 공급사 매칭 없이 ERP 노드를 타므로
    # 자재 카테고리 첫 자재로 폴백하며, 그 사실을 warnings에 남긴다(analyze_kg_context_node).
    # ⚠️ 게이트를 열면 모든 건이 LLM 노드를 타므로 호출 비용이 실제로 발생한다.
    kg_gate_enabled: bool = True


def _as_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


@lru_cache
def get_settings() -> Settings:
    return Settings(
        environment=os.getenv("APP_ENV", "local"),
        mock_mode=_as_bool(os.getenv("MOCK_MODE", "true")),
        severity_rule_version=os.getenv("SEVERITY_RULE_VERSION", "severity-rule-v1"),
        chunk_size=int(os.getenv("RAG_CHUNK_SIZE", "900")),
        chunk_overlap=int(os.getenv("RAG_CHUNK_OVERLAP", "120")),
        embedding_provider=os.getenv("EMBEDDING_PROVIDER", "mock").strip().lower(),
        embedding_dimension=int(os.getenv("EMBEDDING_DIMENSION", "1536")),
        chroma_mode=os.getenv("CHROMA_MODE", "persistent").strip().lower(),
        chroma_host=os.getenv("CHROMA_HOST", "localhost").strip(),
        chroma_port=int(os.getenv("CHROMA_PORT", "8001")),
        chroma_ssl=_as_bool(os.getenv("CHROMA_SSL", "false")),
        chroma_persist_directory=os.getenv("CHROMA_PERSIST_DIRECTORY", "./data/chroma").strip(),
        chroma_collection_prefix=os.getenv(
            "CHROMA_COLLECTION_PREFIX", "contract_documents"
        ).strip(),
        openai_api_key=os.getenv("OPENAI_API_KEY", "").strip(),
        openai_embedding_model=os.getenv(
            "OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"
        ).strip(),
        spring_base_url=os.getenv("SPRING_BASE_URL", "http://localhost:8080").strip(),
        kg_service_base_url=os.getenv(
            "KG_SERVICE_BASE_URL", "http://host.docker.internal:8100"
        ).strip(),
        kg_gate_enabled=_as_bool(os.getenv("KG_GATE_ENABLED", "true")),
    )
