from dataclasses import dataclass
from functools import lru_cache
import os


@dataclass(frozen=True)
class Settings:
    app_name: str = "Battery Raw Material Supply Chain Risk AI API"
    app_version: str = "0.1.0"
    environment: str = "local"
    mock_mode: bool = True
    model_version: str = "xgboost-impact-domain-v0.1"
    severity_rule_version: str = "severity-rule-v0.1"
    chunk_size: int = 900
    chunk_overlap: int = 120
    embedding_provider: str = "mock"
    embedding_dimension: int = 1536


def _as_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


@lru_cache
def get_settings() -> Settings:
    return Settings(
        environment=os.getenv("APP_ENV", "local"),
        mock_mode=_as_bool(os.getenv("MOCK_MODE", "true")),
        model_version=os.getenv("MODEL_VERSION", "xgboost-impact-domain-v0.1"),
        severity_rule_version=os.getenv("SEVERITY_RULE_VERSION", "severity-rule-v0.1"),
        chunk_size=int(os.getenv("RAG_CHUNK_SIZE", "900")),
        chunk_overlap=int(os.getenv("RAG_CHUNK_OVERLAP", "120")),
        embedding_provider=os.getenv("EMBEDDING_PROVIDER", "mock").strip().lower(),
        embedding_dimension=int(os.getenv("EMBEDDING_DIMENSION", "1536")),
    )
