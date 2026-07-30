import re
import logging
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import chromadb
import httpx
from chromadb.config import Settings as ChromaClientSettings

from app.core.config import Settings, get_settings
from app.core.exceptions import (
    RagFilterRequired,
    VectorStoreDeleteFailed,
    VectorStoreFailed,
    VectorStoreUnavailable,
)
from app.services.embedding_service import EmbeddingProvider


logger = logging.getLogger(__name__)


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
        "document_type",
        "content_hash",
    )
    # 인바운드(공급사 계약, supplier_id+material_id)와 아웃바운드(고객사 계약, product_id+
    # customer_id) 문서가 같은 컬렉션을 공유한다 — 둘 다 필수는 아니고 최소 한 쌍은 있어야 한다.
    _OPTIONAL_ID_FIELDS = ("supplier_id", "material_id", "product_id", "customer_id")
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
            cleanup_failed = False
            for document_id in document_ids:
                try:
                    self.delete_document(document_id)
                except Exception:
                    cleanup_failed = True
                    logger.exception(
                        "Failed to clean partially stored Chroma chunks: document_id=%s",
                        document_id,
                    )
            if self._is_connection_error(exception):
                raise VectorStoreUnavailable() from exception
            message = "ChromaDB 문서 청크 저장에 실패했습니다."
            if cleanup_failed:
                message = "ChromaDB 부분 적재 실패 후 청크 정리에도 실패했습니다."
            raise VectorStoreFailed(message) from exception

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
        product_id: int | None = None,
        customer_id: int | None = None,
        top_k: int = 5,
    ) -> list[VectorSearchResult]:
        if contract_id is None and supplier_id is None and product_id is None:
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
        if product_id is not None:
            conditions.append({"product_id": product_id})
        if customer_id is not None:
            conditions.append({"customer_id": customer_id})
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
            self._raise_operation_error(exception, "ChromaDB 문서 검색에 실패했습니다.")

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
            self._raise_operation_error(exception, "ChromaDB 문서 청크 조회에 실패했습니다.")

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
                include=["metadatas"],
            )
            ids = existing.get("ids") or []
            if ids:
                self._collection.delete(ids=ids)
            return len(ids)
        except Exception as exception:
            if self._is_connection_error(exception):
                raise VectorStoreUnavailable() from exception
            raise VectorStoreDeleteFailed() from exception

    def clear(self) -> int:
        try:
            existing = self._collection.get(include=["metadatas"])
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

        for field in self._OPTIONAL_ID_FIELDS:
            if isinstance(chunk, Mapping):
                values[field] = chunk.get(field)
            else:
                values[field] = getattr(chunk, field, None)

        if not isinstance(values["document_id"], str) or not values["document_id"].strip():
            raise VectorStoreFailed("document_id가 올바르지 않습니다.")
        if not isinstance(values["content"], str) or not values["content"].strip():
            raise VectorStoreFailed("content가 비어 있습니다.")
        if not isinstance(values["document_type"], str) or not values["document_type"].strip():
            raise VectorStoreFailed("document_type이 올바르지 않습니다.")
        for field in ("contract_id", "page_number"):
            if not isinstance(values[field], int) or isinstance(values[field], bool) or values[field] <= 0:
                raise VectorStoreFailed(f"{field}는 양의 정수여야 합니다.")
        for field in self._OPTIONAL_ID_FIELDS:
            value = values[field]
            if value is None:
                continue
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                raise VectorStoreFailed(f"{field}는 양의 정수여야 합니다.")
        has_inbound = values["supplier_id"] is not None and values["material_id"] is not None
        has_outbound = values["product_id"] is not None and values["customer_id"] is not None
        if not (has_inbound or has_outbound):
            raise VectorStoreFailed(
                "supplier_id+material_id 또는 product_id+customer_id 중 하나는 필요합니다."
            )
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
        metadata = {
            "document_id": chunk["document_id"],
            "contract_id": chunk["contract_id"],
            "document_type": chunk["document_type"],
            "chunk_index": chunk["chunk_index"],
            "page_number": chunk["page_number"],
            "content_hash": chunk["content_hash"],
            "embedding_type": self.embedding_type,
            "embedding_version": self.embedding_version,
            "mock_embedding": self.mock_embedding,
        }
        # Chroma 메타데이터는 None 값을 허용하지 않으므로, 값이 있는 optional id 필드만 넣는다.
        for field in self._OPTIONAL_ID_FIELDS:
            if chunk.get(field) is not None:
                metadata[field] = chunk[field]
        return metadata

    @staticmethod
    def _vector_id(chunk: dict[str, Any]) -> str:
        return f"{chunk['document_id']}:{chunk['chunk_index']}"

    @staticmethod
    def _cosine_similarity(distance: float) -> float:
        return round(max(0.0, min(1.0, 1.0 - float(distance))), 6)

    @staticmethod
    def _is_connection_error(exception: BaseException) -> bool:
        current: BaseException | None = exception
        visited: set[int] = set()
        while current is not None and id(current) not in visited:
            visited.add(id(current))
            if isinstance(
                current,
                (httpx.TimeoutException, httpx.NetworkError, ConnectionError, OSError),
            ):
                return True
            message = str(current).casefold()
            if any(token in message for token in (
                "connection refused",
                "connection reset",
                "failed to establish a new connection",
                "timed out",
                "all connection attempts failed",
            )):
                return True
            current = current.__cause__ or current.__context__
        return False

    @classmethod
    def _raise_operation_error(cls, exception: Exception, message: str) -> None:
        if cls._is_connection_error(exception):
            raise VectorStoreUnavailable() from exception
        raise VectorStoreFailed(message) from exception
