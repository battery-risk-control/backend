"""1계층 구매팀 계약·RAG 화면 전용 Chroma 검색.

기존 :mod:`app.services.rag_service` / :class:`ChromaVectorStore` 는 멀티에이전트가 쓰는
경로라 **한 줄도 건드리지 않는다.** 그쪽 `search()`는 contract_id/supplier_id/product_id 중
하나를 반드시 요구하는데(`RagFilterRequired`), 이 화면은 검색창에 단어만 넣고 전체 계약을
훑어야 해서 규칙이 정반대다. 그래서 같은 컬렉션을 **읽기 전용**으로 따로 여는 서비스를 새로 둔다.

- 임베딩 provider와 컬렉션 이름은 기존 :class:`ChromaVectorStore`가 계산한 값을 그대로 읽어 쓴다
  (이름이 어긋나면 적재된 청크를 못 찾으므로 파생 규칙을 복제하지 않는다).
- Chroma 클라이언트만 자체 생성한다 — 기존 인스턴스의 private 컬렉션에 손대지 않기 위해서다.
- 쓰기(upsert/delete)는 하지 않는다. 적재는 지금처럼 `/api/v1/documents/process`가 담당한다.
"""

import logging
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

import chromadb
from chromadb.config import Settings as ChromaClientSettings

from app.api.dependencies import get_embedding_service, get_vector_store
from app.core.config import Settings, get_settings
from app.core.exceptions import VectorStoreFailed, VectorStoreUnavailable

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ContractChunkHit:
    document_id: str
    contract_id: int
    document_type: str
    chunk_index: int
    page_number: int
    content: str
    content_hash: str
    similarity_score: float
    supplier_id: int | None
    material_id: int | None
    product_id: int | None
    customer_id: int | None


class ContractRagSearchService:
    def __init__(self, *, client: Any | None = None) -> None:
        self.settings: Settings = get_settings()

        # 적재 경로가 실제로 쓰는 컬렉션·임베딩을 그대로 따라간다.
        vector_store = get_vector_store()
        self.collection_name = vector_store.collection_name
        self.embedding_type = vector_store.embedding_type
        self.embedding_version = vector_store.embedding_version
        self.mock_embedding = vector_store.mock_embedding
        self.embedding_provider = get_embedding_service()

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

    def search(
        self,
        query: str,
        *,
        kind: str = "ALL",
        contract_id: int | None = None,
        supplier_id: int | None = None,
        material_id: int | None = None,
        product_id: int | None = None,
        customer_id: int | None = None,
        top_k: int = 5,
    ) -> list[ContractChunkHit]:
        """필터 없이도 검색한다. 필터를 주면 그만큼 좁힌다.

        `kind`가 INBOUND/OUTBOUND면 매입/납품 청크로 범위를 좁힌다. 인바운드 청크에는
        supplier_id, 아웃바운드 청크에는 product_id만 저장돼 있고(값 없는 optional 필드는
        아예 넣지 않는다) Chroma는 비교 연산자를 건 키가 없는 문서를 제외하므로,
        `{"supplier_id": {"$gte": 0}}` 하나로 "매입 계약만"을 골라낼 수 있다.
        """
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
        # 특정 id 조건이 없을 때만 kind로 매입/납품 전체를 가른다 — id를 이미 주면
        # 그 계약이 어느 쪽인지 자명하므로 존재필터를 겹칠 필요가 없다.
        if kind == "INBOUND" and supplier_id is None and material_id is None:
            conditions.append({"supplier_id": {"$gte": 0}})
        elif kind == "OUTBOUND" and product_id is None and customer_id is None:
            conditions.append({"product_id": {"$gte": 0}})
        # 조건이 없으면 where 자체를 넘기지 않는다 — 컬렉션 전체가 검색 대상이 된다.
        where = None
        if len(conditions) == 1:
            where = conditions[0]
        elif conditions:
            where = {"$and": conditions}

        try:
            query_embedding = self.embedding_provider.embed_query(query)
            result = self._collection.query(
                query_embeddings=[query_embedding],
                n_results=top_k,
                where=where,
                include=["documents", "metadatas", "distances"],
            )
        except Exception as exception:
            logger.warning("계약 조항 검색 실패: %s", exception)
            raise VectorStoreFailed("ChromaDB 계약 조항 검색에 실패했습니다.") from exception

        documents = (result.get("documents") or [[]])[0]
        metadatas = (result.get("metadatas") or [[]])[0]
        distances = (result.get("distances") or [[]])[0]
        return [
            ContractChunkHit(
                document_id=str(metadata.get("document_id", "")),
                contract_id=int(metadata.get("contract_id", 0)),
                document_type=str(metadata.get("document_type", "")),
                chunk_index=int(metadata.get("chunk_index", 0)),
                page_number=int(metadata.get("page_number", 1)),
                content=content or "",
                content_hash=str(metadata.get("content_hash", "")),
                similarity_score=_cosine_similarity(distance),
                supplier_id=_optional_int(metadata, "supplier_id"),
                material_id=_optional_int(metadata, "material_id"),
                product_id=_optional_int(metadata, "product_id"),
                customer_id=_optional_int(metadata, "customer_id"),
            )
            for content, metadata, distance in zip(
                documents, metadatas, distances, strict=True
            )
        ]


def _optional_int(metadata: dict, key: str) -> int | None:
    value = metadata.get(key)
    return int(value) if value is not None else None


def _cosine_similarity(distance: float | None) -> float:
    """Chroma cosine distance(0~2)를 유사도(0~1)로 되돌린다.

    기존 :class:`ChromaVectorStore`와 같은 환산식을 써야 화면의 유사도와 멀티에이전트가
    보는 값이 어긋나지 않는다.
    """
    if distance is None:
        return 0.0
    return round(max(0.0, min(1.0, 1.0 - float(distance))), 6)


@lru_cache
def get_contract_rag_search_service() -> ContractRagSearchService:
    return ContractRagSearchService()
