from dataclasses import dataclass
from typing import Protocol

from app.rag.chunker import DocumentChunk


@dataclass(frozen=True)
class StoredChunk:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    chunk: DocumentChunk


class VectorStore(Protocol):
    def add(self, chunks: list[StoredChunk]) -> None: ...
    def search(self, query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunk]: ...


class InMemoryVectorStore:
    """Metadata-filtered placeholder for the future ChromaDB adapter."""

    def __init__(self) -> None:
        self._chunks: list[StoredChunk] = []

    def add(self, chunks: list[StoredChunk]) -> None:
        self._chunks.extend(chunks)

    def search(self, _query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunk]:
        matches = [
            item for item in self._chunks
            if (contract_id is None or item.contract_id == contract_id)
            and (supplier_id is None or item.supplier_id == supplier_id)
        ]
        return matches[:top_k]
