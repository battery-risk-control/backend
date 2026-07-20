import pytest

from app.api.dependencies import get_rag_service, reset_dependencies


@pytest.fixture(autouse=True)
def isolate_in_memory_dependencies():
    reset_dependencies()
    get_rag_service().clear()
    yield
    reset_dependencies()
