from dataclasses import dataclass
from uuid import uuid4

from app.services.document_service import DocumentService, InMemoryDocumentStore
from app.services.vector_store_service import ChromaVectorStore


@dataclass(frozen=True)
class UploadSummary:
    document_id: str
    file_name: str
    chunk_count: int
    embedding_type: str
    embedding_version: str
    mock_embedding: bool


@dataclass(frozen=True)
class StoredChunkView:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    chunk_index: int
    page_number: int
    content_hash: str
    content: str
    embedding_type: str
    embedding_version: str
    mock_embedding: bool
    similarity_score: float


class RagService:
    def __init__(
        self,
        store: InMemoryDocumentStore | None = None,
        vector_store: ChromaVectorStore | None = None,
    ) -> None:
        self._store = store or InMemoryDocumentStore()
        self._vector_store = vector_store
        self._documents = DocumentService(self._store, vector_store)

    @property
    def mock_embedding(self) -> bool:
        return self._vector_store.mock_embedding if self._vector_store else True

    def upload(self, content: bytes, file_name: str, contract_id: int, supplier_id: int, material_id: int, document_type: str) -> UploadSummary:
        document, _duplicate = self._documents.process(
            content, file_name, contract_id, supplier_id, material_id, document_type,
            document_id=f"LEGACY-{uuid4().hex[:12].upper()}",
        )
        return UploadSummary(
            document.document_id,
            file_name,
            len(document.chunks),
            document.embedding_type,
            document.embedding_version,
            document.mock_embedding,
        )

    def search(
        self,
        query: str,
        contract_id: int | None,
        supplier_id: int | None,
        top_k: int,
        material_id: int | None = None,
    ) -> list[StoredChunkView]:
        if self._vector_store is not None:
            vector_results = self._vector_store.search(
                query,
                contract_id=contract_id,
                supplier_id=supplier_id,
                material_id=material_id,
                top_k=top_k,
            )
            return [
                StoredChunkView(
                    document_id=str(item.metadata["document_id"]),
                    contract_id=int(item.metadata["contract_id"]),
                    supplier_id=int(item.metadata["supplier_id"]),
                    material_id=int(item.metadata["material_id"]),
                    document_type=str(item.metadata["document_type"]),
                    chunk_index=int(item.metadata["chunk_index"]),
                    page_number=int(item.metadata["page_number"]),
                    content_hash=str(item.metadata["content_hash"]),
                    content=item.content,
                    embedding_type=str(item.metadata["embedding_type"]),
                    embedding_version=str(item.metadata["embedding_version"]),
                    mock_embedding=bool(item.metadata["mock_embedding"]),
                    similarity_score=item.similarity_score,
                )
                for item in vector_results
            ]

        results: list[StoredChunkView] = []
        for document in self._store.search(query, contract_id, supplier_id):
            if material_id is not None and document.material_id != material_id:
                continue
            results.extend(
                StoredChunkView(
                    document.document_id,
                    document.contract_id,
                    document.supplier_id,
                    document.material_id,
                    document.document_type,
                    chunk.chunk_index,
                    chunk.page_number,
                    chunk.content_hash,
                    chunk.content,
                    document.embedding_type,
                    document.embedding_version,
                    document.mock_embedding,
                    1.0 if query.casefold() in chunk.content.casefold() else 0.0,
                )
                for chunk in document.chunks
            )
        return results[:top_k]

    def clear(self) -> None:
        self._store.clear()
        if self._vector_store is not None:
            self._vector_store.clear()
