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
    )
