import re
from collections import Counter
from hashlib import sha256
from math import sqrt
from typing import Protocol, runtime_checkable

from app.core.config import Settings, get_settings
from app.core.exceptions import ModelUnavailable


@runtime_checkable
class EmbeddingProvider(Protocol):
    """ChromaDB가 사용할 수 있는 최소 Embedding 계약입니다."""

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        ...

    def embed_query(self, text: str) -> list[float]:
        ...


class MockEmbedding:
    """외부 모델 없이 재현 가능한 로컬 Hashing Embedding입니다."""

    provider_name = "mock"
    model_name = "mock-hashing-v1"
    embedding_type = "MOCK_TOKEN_HASH"
    embedding_version = "mock-v1"
    mock_embedding = True
    _TOKEN_PATTERN = re.compile(r"\w+", re.UNICODE)

    def __init__(self, dimension: int = 1536) -> None:
        if dimension <= 0:
            raise ValueError("embedding_dimension은 1 이상이어야 합니다.")
        self.dimension = dimension

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self._embed(text) for text in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        if not isinstance(text, str) or not text.strip():
            raise ValueError("Embedding 입력 텍스트는 비어 있을 수 없습니다.")

        tokens = self._TOKEN_PATTERN.findall(text.casefold())
        if not tokens:
            raise ValueError("Embedding할 수 있는 토큰을 찾지 못했습니다.")

        vector = [0.0] * self.dimension
        for token, count in Counter(tokens).items():
            digest = sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:8], byteorder="big") % self.dimension
            sign = 1.0 if digest[8] & 1 else -1.0
            vector[index] += sign * count

        norm = sqrt(sum(value * value for value in vector))
        if norm == 0:
            raise ValueError("Mock Embedding 벡터를 생성하지 못했습니다.")
        return [value / norm for value in vector]


class OpenAIEmbedding:
    """OpenAI Embedding API를 사용하는 실제 Provider입니다 (S16 교체 대상)."""

    provider_name = "openai"
    embedding_type = "OPENAI_API"
    mock_embedding = False

    def __init__(
        self,
        api_key: str,
        model: str = "text-embedding-3-small",
        dimension: int = 1536,
    ) -> None:
        if not api_key or not api_key.strip():
            raise ModelUnavailable()
        if dimension <= 0:
            raise ValueError("embedding_dimension은 1 이상이어야 합니다.")
        # 지연 import: mock 경로와 단위 테스트가 openai 패키지에 의존하지 않도록 한다.
        from openai import OpenAI

        self._client = OpenAI(api_key=api_key.strip())
        self.model = model
        self.dimension = dimension
        self.embedding_version = f"openai-{model}"

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        cleaned = self._validate(texts)
        response = self._client.embeddings.create(
            model=self.model, input=cleaned, dimensions=self.dimension
        )
        return [item.embedding for item in response.data]

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]

    @staticmethod
    def _validate(texts: list[str]) -> list[str]:
        if not texts:
            raise ValueError("Embedding 입력이 비어 있습니다.")
        for text in texts:
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Embedding 입력 텍스트는 비어 있을 수 없습니다.")
        return texts


def get_embedding_provider(
    settings: Settings | None = None,
    *,
    openai_provider: EmbeddingProvider | None = None,
) -> EmbeddingProvider:
    """설정에 따라 Mock 또는 외부에서 주입한 OpenAI Provider를 반환합니다."""

    resolved_settings = settings or get_settings()
    provider_name = resolved_settings.embedding_provider.strip().lower()

    if provider_name == "mock":
        return MockEmbedding(dimension=resolved_settings.embedding_dimension)
    if provider_name == "openai":
        if openai_provider is None:
            raise ModelUnavailable()
        if not isinstance(openai_provider, EmbeddingProvider):
            raise TypeError("openai_provider가 EmbeddingProvider 계약을 충족하지 않습니다.")
        return openai_provider
    raise ValueError("EMBEDDING_PROVIDER는 mock 또는 openai여야 합니다.")
