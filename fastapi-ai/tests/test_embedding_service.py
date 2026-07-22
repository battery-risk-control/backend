from math import isclose, sqrt

import pytest

from app.core.config import Settings, get_settings
from app.core.exceptions import ModelUnavailable
from app.services.embedding_service import MockEmbedding, get_embedding_provider


def test_embedding_configuration_defaults_to_mock_1536_dimensions() -> None:
    settings = get_settings()
    assert settings.embedding_provider == "mock"
    assert settings.embedding_dimension == 1536


def test_mock_embedding_is_deterministic_and_normalized() -> None:
    provider = MockEmbedding(dimension=1536)
    text = "리튬 공급 계약 가격 조정 조항"

    first = provider.embed_query(text)
    second = provider.embed_query(text)

    assert first == second
    assert len(first) == 1536
    assert isclose(sqrt(sum(value * value for value in first)), 1.0)


def test_document_batch_and_query_use_the_same_embedding_contract() -> None:
    provider = MockEmbedding(dimension=64)
    texts = ["nickel supply risk", "lithium price clause"]

    batch = provider.embed_documents(texts)

    assert len(batch) == 2
    assert all(len(vector) == 64 for vector in batch)
    assert batch[0] == provider.embed_query(texts[0])
    assert batch[1] == provider.embed_query(texts[1])


def test_mock_embedding_rejects_empty_text() -> None:
    provider = MockEmbedding()
    with pytest.raises(ValueError, match="비어 있을 수 없습니다"):
        provider.embed_query("   ")


def test_provider_factory_returns_mock_by_default() -> None:
    provider = get_embedding_provider(Settings())
    assert isinstance(provider, MockEmbedding)
    assert provider.dimension == 1536


def test_provider_factory_accepts_injected_openai_compatible_provider() -> None:
    class FakeOpenAiEmbedding:
        def embed_documents(self, texts: list[str]) -> list[list[float]]:
            return [[1.0] for _text in texts]

        def embed_query(self, text: str) -> list[float]:
            return [1.0]

    openai_provider = FakeOpenAiEmbedding()
    selected = get_embedding_provider(
        Settings(embedding_provider="openai"),
        openai_provider=openai_provider,
    )
    assert selected is openai_provider


def test_openai_provider_requires_external_injection() -> None:
    with pytest.raises(ModelUnavailable):
        get_embedding_provider(Settings(embedding_provider="openai"))


def test_unknown_embedding_provider_is_rejected() -> None:
    with pytest.raises(ValueError, match="mock 또는 openai"):
        get_embedding_provider(Settings(embedding_provider="unknown"))
