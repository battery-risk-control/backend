from dataclasses import dataclass

from app.rag.chunker import ContractChunker
from app.rag.loader import DocumentLoader
from app.rag.vector_store import InMemoryVectorStore, StoredChunk, VectorStore


@dataclass(frozen=True)
class UploadSummary:
    document_id: str
    file_name: str
    chunk_count: int


class RagService:
    def __init__(self, vector_store: VectorStore | None = None) -> None:
        self._loader = DocumentLoader()
        self._chunker = ContractChunker()
        self._vector_store = vector_store or InMemoryVectorStore()

    def upload(self, content: bytes, file_name: str, contract_id: int, supplier_id: int, material_id: int, document_type: str) -> UploadSummary:
        document = self._loader.load(content, file_name)
        document_id = f"DOC-{contract_id}-001"
        chunks = self._chunker.chunk(document.text)
        self._vector_store.add([
            StoredChunk(document_id, contract_id, supplier_id, material_id, document_type, chunk)
            for chunk in chunks
        ])
        return UploadSummary(document_id, file_name, len(chunks))

    def search(self, query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunk]:
        return self._vector_store.search(query, contract_id, supplier_id, top_k)


rag_service = RagService()
