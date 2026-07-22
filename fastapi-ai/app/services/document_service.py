from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from threading import Lock
from uuid import uuid4

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from app.core.config import get_settings


class EmptyDocumentError(ValueError):
    pass


class UnsupportedDocumentError(ValueError):
    pass


@dataclass(frozen=True)
class DocumentChunk:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    file_name: str
    content: str
    chunk_index: int
    page_number: int = 1


@dataclass(frozen=True)
class ProcessedDocument:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    file_name: str
    content_hash: str
    chunks: tuple[DocumentChunk, ...]


class InMemoryDocumentStore:
    def __init__(self) -> None:
        self._documents: dict[str, ProcessedDocument] = {}
        self._hash_index: dict[tuple[int, str], str] = {}
        self._lock = Lock()

    def find_by_hash(self, contract_id: int, content_hash: str) -> ProcessedDocument | None:
        document_id = self._hash_index.get((contract_id, content_hash))
        return self._documents.get(document_id) if document_id else None

    def save(self, document: ProcessedDocument) -> ProcessedDocument:
        with self._lock:
            self._documents[document.document_id] = document
            self._hash_index[(document.contract_id, document.content_hash)] = document.document_id
        return document

    def search(self, query: str, contract_id: int | None, supplier_id: int | None) -> list[ProcessedDocument]:
        words = [word.lower() for word in query.split() if word]
        filtered = [document for document in self._documents.values()
                    if (contract_id is None or document.contract_id == contract_id)
                    and (supplier_id is None or document.supplier_id == supplier_id)]
        return sorted(filtered, key=lambda document: any(
            word in chunk.content.lower() for chunk in document.chunks for word in words
        ), reverse=True)

    def clear(self) -> None:
        with self._lock:
            self._documents.clear()
            self._hash_index.clear()


class DocumentService:
    def __init__(self, store: InMemoryDocumentStore | None = None) -> None:
        self.store = store or InMemoryDocumentStore()

    def process(self, content: bytes, file_name: str, contract_id: int,
                supplier_id: int, material_id: int, document_type: str,
                document_id: str | None = None) -> tuple[ProcessedDocument, bool]:
        content_hash = sha256(content).hexdigest()
        existing = self.store.find_by_hash(contract_id, content_hash)
        if existing:
            return existing, True

        text = self._extract_text(content, file_name)
        if not text:
            raise EmptyDocumentError("문서에서 처리할 텍스트를 찾을 수 없습니다.")

        settings = get_settings()
        resolved_document_id = document_id or f"LEGACY-{uuid4().hex[:12].upper()}"
        chunks = tuple(
            DocumentChunk(resolved_document_id, contract_id, supplier_id, material_id, document_type,
                          file_name, chunk, index)
            for index, chunk in enumerate(
                self._chunk_text(text, settings.chunk_size, settings.chunk_overlap))
        )
        document = ProcessedDocument(resolved_document_id, contract_id, supplier_id, material_id,
                                     document_type, file_name, content_hash, chunks)
        return self.store.save(document), False

    def _extract_text(self, content: bytes, file_name: str) -> str:
        suffix = Path(file_name).suffix.lower()
        if suffix == ".txt":
            try:
                return content.decode("utf-8-sig").strip()
            except UnicodeDecodeError as exception:
                raise UnsupportedDocumentError("TXT 파일은 UTF-8이어야 합니다.") from exception
        if suffix != ".pdf":
            raise UnsupportedDocumentError("PDF와 TXT 파일만 지원합니다.")
        try:
            reader = PdfReader(BytesIO(content))
            return "\n".join(page.extract_text() or "" for page in reader.pages).strip()
        except PdfReadError as exception:
            raise UnsupportedDocumentError("올바른 PDF 파일이 아닙니다.") from exception

    @staticmethod
    def _chunk_text(text: str, chunk_size: int, overlap: int) -> list[str]:
        chunks: list[str] = []
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            chunk = text[start:end].strip()
            if chunk:
                chunks.append(chunk)
            if end == len(text):
                break
            start = max(end - overlap, start + 1)
        return chunks
