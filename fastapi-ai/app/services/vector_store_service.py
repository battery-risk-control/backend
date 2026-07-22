import re
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import chromadb
from chromadb.config import Settings as ChromaClientSettings

from app.core.config import Settings, get_settings
from app.core.exceptions import (
    RagFilterRequired,
    VectorStoreDeleteFailed,
    VectorStoreFailed,
    VectorStoreUnavailable,
)
from app.services.embedding_service import EmbeddingProvider


@dataclass(frozen=True)
class VectorStoreWriteResult:
    document_ids: tuple[str, ...]
    chunk_count: int
    embedding_type: str
    embedding_version: str
    mock_embedding: bool


@dataclass(frozen=True)
class VectorSearchResult:
    vector_id: str
    content: str
    metadata: dict[str, Any]
    similarity_score: float


class ChromaVectorStore:
    """4단계 청크를 ChromaDB에 저장하고 필터 검색하는 전용 경계입니다."""

    _REQUIRED_FIELDS = (
        "document_id",
        "chunk_index",
        "page_number",
        "content",
        "contract_id",
        "supplier_id",
        "material_id",
        "document_type",
        "content_hash",
    )
    _HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")

    def __init__(
        self,
        embedding_provider: EmbeddingProvider,
        settings: Settings | None = None,
        *,
        client: Any | None = None,
    ) -> None:
        self.embedding_provider = embedding_provider
        self.settings = settings or get_settings()
        self.embedding_type = str(
            getattr(embedding_provider, "embedding_type", "UNKNOWN")
        )
        self.embedding_version = str(
            getattr(embedding_provider, "embedding_version", "unknown-v1")
        )
        self.mock_embedding = bool(
            getattr(embedding_provider, "mock_embedding", False)
        )
        provider_name = str(
            getattr(embedding_provider, "provider_name", self.settings.embedding_provider)
        ).strip().lower()
        provider_suffix = "mock_v1" if self.mock_embedding else f"{provider_name}_v1"
        self.collection_name = (
            f"{self.settings.chroma_collection_prefix}_{provider_suffix}"
        )

        try:
            self._client = client or self._create_client()
            self._collection = self._client.get_or_create_collection(
                name=self.collection_name,
                metadata={"hnsw:space": "cosine"},
            )
        except Exception as exception:
            raise VectorStoreUnavailable() from exception

    def _create_client(self) -> Any:
        mode = self.settings.chroma_mode
        if mode == "http":
            return chromadb.HttpClient(
                host=self.settings.chroma_host,
                port=self.settings.chroma_port,
                ssl=self.settings.chroma_ssl,
            )
        if mode == "persistent":
            return chromadb.PersistentClient(
                path=self.settings.chroma_persist_directory,
            )
        if mode == "ephemeral":
            return chromadb.EphemeralClient(
                settings=ChromaClientSettings(allow_reset=True),
            )
        raise ValueError("CHROMA_MODE는 persistent, http, ephemeral 중 하나여야 합니다.")

    def health_check(self) -> dict[str, Any]:
        try:
            heartbeat = self._client.heartbeat()
            return {
                "status": "UP",
                "collection_name": self.collection_name,
                "chunk_count": self._collection.count(),
                "heartbeat": heartbeat,
            }
        except Exception as exception:
            raise VectorStoreUnavailable() from exception

    def upsert_chunks(self, chunks: Sequence[Any]) -> VectorStoreWriteResult:
        normalized = [self._normalize_chunk(chunk) for chunk in chunks]
        if not normalized:
            raise VectorStoreFailed("저장할 문서 청크가 없습니다.")

        ids = [self._vector_id(chunk) for chunk in normalized]
        if len(ids) != len(set(ids)):
            raise VectorStoreFailed("중복된 document_id와 chunk_index가 있습니다.")

        try:
            embeddings = self.embedding_provider.embed_documents(
                [chunk["content"] for chunk in normalized]
            )
        except Exception as exception:
            raise VectorStoreFailed("문서 청크 Embedding 생성에 실패했습니다.") from exception
        if len(embeddings) != len(normalized):
            raise VectorStoreFailed("Embedding 개수가 문서 청크 개수와 일치하지 않습니다.")

        document_ids = tuple(sorted({chunk["document_id"] for chunk in normalized}))
        for document_id in document_ids:
            self.delete_document(document_id)

        metadatas = [self._metadata(chunk) for chunk in normalized]
        try:
            self._collection.upsert(
                ids=ids,
                embeddings=embeddings,
                documents=[chunk["content"] for chunk in normalized],
                metadatas=metadatas,
            )
        except Exception as exception:
            raise VectorStoreFailed("ChromaDB 문서 청크 저장에 실패했습니다.") from exception

        return VectorStoreWriteResult(
            document_ids=document_ids,
            chunk_count=len(normalized),
            embedding_type=self.embedding_type,
            embedding_version=self.embedding_version,
            mock_embedding=self.mock_embedding,
        )

    def search(
        self,
        query: str,
        *,
        contract_id: int | None,
        supplier_id: int | None,
        material_id: int | None = None,
        top_k: int = 5,
    ) -> list[VectorSearchResult]:
        if contract_id is None and supplier_id is None:
            raise RagFilterRequired()
        if not query.strip():
            raise VectorStoreFailed("검색어는 비어 있을 수 없습니다.")

        conditions: list[dict[str, Any]] = []
        if contract_id is not None:
            conditions.append({"contract_id": contract_id})
        if supplier_id is not None:
            conditions.append({"supplier_id": supplier_id})
        if material_id is not None:
            conditions.append({"material_id": material_id})
        where = conditions[0] if len(conditions) == 1 else {"$and": conditions}

        try:
            query_embedding = self.embedding_provider.embed_query(query)
            result = self._collection.query(
                query_embeddings=[query_embedding],
                n_results=top_k,
                where=where,
                include=["documents", "metadatas", "distances"],
            )
        except Exception as exception:
            raise VectorStoreFailed("ChromaDB 문서 검색에 실패했습니다.") from exception

        ids = (result.get("ids") or [[]])[0]
        documents = (result.get("documents") or [[]])[0]
        metadatas = (result.get("metadatas") or [[]])[0]
        distances = (result.get("distances") or [[]])[0]
        return [
            VectorSearchResult(
                vector_id=vector_id,
                content=content or "",
                metadata=dict(metadata or {}),
                similarity_score=self._cosine_similarity(distance),
            )
            for vector_id, content, metadata, distance in zip(
                ids, documents, metadatas, distances, strict=True
            )
        ]

    def get_document(self, document_id: str) -> list[dict[str, Any]]:
        try:
            result = self._collection.get(
                where={"document_id": document_id},
                include=["documents", "metadatas"],
            )
        except Exception as exception:
            raise VectorStoreFailed("ChromaDB 문서 청크 조회에 실패했습니다.") from exception

        documents = result.get("documents") or []
        metadatas = result.get("metadatas") or []
        return [
            {
                "id": vector_id,
                "content": content or "",
                "metadata": dict(metadata or {}),
            }
            for vector_id, content, metadata in zip(
                result.get("ids") or [], documents, metadatas, strict=True
            )
        ]

    def delete_document(self, document_id: str) -> int:
        try:
            existing = self._collection.get(
                where={"document_id": document_id},
                include=[],
            )
            ids = existing.get("ids") or []
            if ids:
                self._collection.delete(ids=ids)
            return len(ids)
        except Exception as exception:
            raise VectorStoreDeleteFailed() from exception

    def clear(self) -> int:
        try:
            existing = self._collection.get(include=[])
            ids = existing.get("ids") or []
            if ids:
                self._collection.delete(ids=ids)
            return len(ids)
        except Exception as exception:
            raise VectorStoreDeleteFailed() from exception

    def _normalize_chunk(self, chunk: Any) -> dict[str, Any]:
        values: dict[str, Any] = {}
        for field in self._REQUIRED_FIELDS:
            if isinstance(chunk, Mapping):
                if field not in chunk:
                    raise VectorStoreFailed(f"필수 청크 필드가 없습니다: {field}")
                values[field] = chunk[field]
            else:
                try:
                    values[field] = getattr(chunk, field)
                except AttributeError as exception:
                    raise VectorStoreFailed(
                        f"필수 청크 필드가 없습니다: {field}"
                    ) from exception

        if not isinstance(values["document_id"], str) or not values["document_id"].strip():
            raise VectorStoreFailed("document_id가 올바르지 않습니다.")
        if not isinstance(values["content"], str) or not values["content"].strip():
            raise VectorStoreFailed("content가 비어 있습니다.")
        if not isinstance(values["document_type"], str) or not values["document_type"].strip():
            raise VectorStoreFailed("document_type이 올바르지 않습니다.")
        for field in ("contract_id", "supplier_id", "material_id", "page_number"):
            if not isinstance(values[field], int) or isinstance(values[field], bool) or values[field] <= 0:
                raise VectorStoreFailed(f"{field}는 양의 정수여야 합니다.")
        if (
            not isinstance(values["chunk_index"], int)
            or isinstance(values["chunk_index"], bool)
            or values["chunk_index"] < 0
        ):
            raise VectorStoreFailed("chunk_index는 0 이상의 정수여야 합니다.")
        content_hash = values["content_hash"]
        if not isinstance(content_hash, str) or not self._HASH_PATTERN.fullmatch(content_hash.lower()):
            raise VectorStoreFailed("content_hash는 SHA-256 형식이어야 합니다.")
        values["content_hash"] = content_hash.lower()
        return values

    def _metadata(self, chunk: dict[str, Any]) -> dict[str, Any]:
        return {
            "document_id": chunk["document_id"],
            "contract_id": chunk["contract_id"],
            "supplier_id": chunk["supplier_id"],
            "material_id": chunk["material_id"],
            "document_type": chunk["document_type"],
            "chunk_index": chunk["chunk_index"],
            "page_number": chunk["page_number"],
            "content_hash": chunk["content_hash"],
            "embedding_type": self.embedding_type,
            "embedding_version": self.embedding_version,
            "mock_embedding": self.mock_embedding,
        }

    @staticmethod
    def _vector_id(chunk: dict[str, Any]) -> str:
        return f"{chunk['document_id']}:{chunk['chunk_index']}"

    @staticmethod
    def _cosine_similarity(distance: float) -> float:
        return round(max(0.0, min(1.0, 1.0 - float(distance))), 6)
