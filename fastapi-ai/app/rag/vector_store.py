from dataclasses import dataclass
from typing import Protocol

from app.rag.chunker import DocumentChunk
from app.rag.embeddings import mock_embed


@dataclass(frozen=True)
class StoredChunk:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    chunk: DocumentChunk
    embedding: tuple[float, ...]


class VectorStore(Protocol):
    def add(self, chunks: list[StoredChunk]) -> None: ...
    def search(self, query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunk]: ...
    def clear(self) -> None: ...


class InMemoryVectorStore:
    """Metadata-filtered placeholder for the future ChromaDB adapter."""

    def __init__(self) -> None:
        self._chunks: list[StoredChunk] = []

    def add(self, chunks: list[StoredChunk]) -> None:
        document_ids = {chunk.document_id for chunk in chunks}
        self._chunks = [item for item in self._chunks if item.document_id not in document_ids]
        self._chunks.extend(chunks)

    def search(self, query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunk]:
        matches = [
            item for item in self._chunks
            if (contract_id is None or item.contract_id == contract_id)
            and (supplier_id is None or item.supplier_id == supplier_id)
        ]
        query_embedding = mock_embed(query)
        matches.sort(
            key=lambda item: sum(a * b for a, b in zip(item.embedding, query_embedding)),
            reverse=True,
        )
        return matches[:top_k]

    def clear(self) -> None:
        self._chunks.clear()
