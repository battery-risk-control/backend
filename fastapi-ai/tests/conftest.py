import pytest

from app.api.dependencies import get_rag_service, reset_dependencies
from app.core.config import get_settings


@pytest.fixture(autouse=True)
def isolate_in_memory_dependencies(monkeypatch):
    monkeypatch.setenv("CHROMA_MODE", "ephemeral")
    get_settings.cache_clear()
    reset_dependencies()
    get_rag_service().clear()
    yield
    reset_dependencies()
    get_settings.cache_clear()
