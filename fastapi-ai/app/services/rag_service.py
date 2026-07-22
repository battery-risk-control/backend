from dataclasses import dataclass

from app.services.document_service import DocumentChunk, DocumentService, InMemoryDocumentStore


@dataclass(frozen=True)
class UploadSummary:
    document_id: str
    file_name: str
    chunk_count: int


@dataclass(frozen=True)
class StoredChunkView:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    chunk: DocumentChunk


class RagService:
    def __init__(self, store: InMemoryDocumentStore | None = None) -> None:
        self._store = store or InMemoryDocumentStore()
        self._documents = DocumentService(self._store)

    def upload(self, content: bytes, file_name: str, contract_id: int, supplier_id: int, material_id: int, document_type: str) -> UploadSummary:
        document, _duplicate = self._documents.process(
            content, file_name, contract_id, supplier_id, material_id, document_type
        )
        return UploadSummary(document.document_id, file_name, len(document.chunks))

    def search(self, query: str, contract_id: int | None, supplier_id: int | None, top_k: int) -> list[StoredChunkView]:
        results: list[StoredChunkView] = []
        for document in self._store.search(query, contract_id, supplier_id):
            results.extend(StoredChunkView(document.document_id, document.contract_id,
                                           document.supplier_id, document.material_id, chunk)
                           for chunk in document.chunks)
        return results[:top_k]

    def clear(self) -> None:
        self._store.clear()


rag_service = RagService()
