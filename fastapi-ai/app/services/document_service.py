import re
from dataclasses import dataclass, replace
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from threading import Lock

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from app.core.config import get_settings
from app.core.exceptions import (
    AppException,
    ChunkingFailed,
    EmptyDocument,
    InvalidPdf,
    TextExtractionFailed,
    UnsupportedDocumentType,
)
from app.services.vector_store_service import ChromaVectorStore


@dataclass(frozen=True)
class ExtractedPage:
    page_number: int
    text: str


@dataclass(frozen=True)
class DocumentChunk:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    file_name: str
    content_hash: str
    content: str
    chunk_index: int
    page_number: int


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
    embedding_type: str
    embedding_version: str
    mock_embedding: bool


class InMemoryDocumentStore:
    """4단계와 검색 Mock 사이의 임시 저장소입니다. ChromaDB 구현 시 교체합니다."""

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
        filtered = [
            document
            for document in self._documents.values()
            if (contract_id is None or document.contract_id == contract_id)
            and (supplier_id is None or document.supplier_id == supplier_id)
        ]
        return sorted(
            filtered,
            key=lambda document: any(
                word in chunk.content.lower()
                for chunk in document.chunks
                for word in words
            ),
            reverse=True,
        )

    def clear(self) -> None:
        with self._lock:
            self._documents.clear()
            self._hash_index.clear()


class DocumentService:
    # 계약서의 한국어/영문 조항 시작점을 우선 경계로 사용합니다.
    _CLAUSE_HEADING = re.compile(
        r"(?im)^\s*(?:"
        r"제\s*\d+\s*조(?:의\s*\d+)?(?:\s*(?:\([^\r\n)]+\)|[-.:]?\s*[^\r\n]+))?"
        r"|article\s+\d+(?:\.\d+)*(?:\s*[-.:]?\s*[^\r\n]*)?"
        r")\s*$"
    )
    _PARAGRAPH_BREAK = re.compile(r"\n\s*\n+")

    def __init__(
        self,
        store: InMemoryDocumentStore | None = None,
        vector_store: ChromaVectorStore | None = None,
    ) -> None:
        self.store = store or InMemoryDocumentStore()
        self.vector_store = vector_store

    def process(
        self,
        content: bytes,
        file_name: str,
        contract_id: int,
        supplier_id: int,
        material_id: int,
        document_type: str,
        document_id: str,
        force_reprocess: bool = False,
    ) -> tuple[ProcessedDocument, bool]:
        content_hash = sha256(content).hexdigest()
        existing = self.store.find_by_hash(contract_id, content_hash)
        if existing:
            if (
                force_reprocess
                and existing.document_id == document_id
                and self.vector_store is not None
            ):
                write_result = self.vector_store.upsert_chunks(existing.chunks)
                refreshed = replace(
                    existing,
                    embedding_type=write_result.embedding_type,
                    embedding_version=write_result.embedding_version,
                    mock_embedding=write_result.mock_embedding,
                )
                return self.store.save(refreshed), False
            return existing, True

        pages = self._extract_pages(content, file_name)
        settings = get_settings()
        try:
            page_chunks = self._chunk_pages(
                pages,
                chunk_size=settings.chunk_size,
                overlap=settings.chunk_overlap,
            )
        except AppException:
            raise
        except Exception as exception:
            raise ChunkingFailed() from exception

        chunks = tuple(
            DocumentChunk(
                document_id=document_id,
                contract_id=contract_id,
                supplier_id=supplier_id,
                material_id=material_id,
                document_type=document_type,
                file_name=file_name,
                content_hash=content_hash,
                content=chunk_text,
                chunk_index=index,
                page_number=page_number,
            )
            for index, (page_number, chunk_text) in enumerate(page_chunks)
        )
        embedding_type = "NOT_STORED"
        embedding_version = "none"
        mock_embedding = True
        if self.vector_store is not None:
            write_result = self.vector_store.upsert_chunks(chunks)
            embedding_type = write_result.embedding_type
            embedding_version = write_result.embedding_version
            mock_embedding = write_result.mock_embedding

        document = ProcessedDocument(
            document_id=document_id,
            contract_id=contract_id,
            supplier_id=supplier_id,
            material_id=material_id,
            document_type=document_type,
            file_name=file_name,
            content_hash=content_hash,
            chunks=chunks,
            embedding_type=embedding_type,
            embedding_version=embedding_version,
            mock_embedding=mock_embedding,
        )
        return self.store.save(document), False

    def _extract_pages(self, content: bytes, file_name: str) -> tuple[ExtractedPage, ...]:
        suffix = Path(file_name).suffix.lower()
        if suffix == ".txt":
            try:
                text = content.decode("utf-8-sig").strip()
            except UnicodeDecodeError as exception:
                raise TextExtractionFailed("TXT 파일은 UTF-8 인코딩이어야 합니다.") from exception
            if not text:
                raise EmptyDocument()
            return (ExtractedPage(page_number=1, text=text),)

        if suffix != ".pdf":
            raise UnsupportedDocumentType(file_name)

        try:
            reader = PdfReader(BytesIO(content))
            if reader.is_encrypted:
                raise InvalidPdf()
            pdf_pages = list(reader.pages)
        except InvalidPdf:
            raise
        except (PdfReadError, EOFError, TypeError, ValueError) as exception:
            raise InvalidPdf() from exception
        except Exception as exception:
            raise InvalidPdf() from exception

        pages: list[ExtractedPage] = []
        for page_number, page in enumerate(pdf_pages, start=1):
            try:
                text = (page.extract_text() or "").strip()
            except Exception as exception:
                raise TextExtractionFailed("PDF 페이지 텍스트 추출에 실패했습니다.") from exception
            if text:
                pages.append(ExtractedPage(page_number=page_number, text=text))

        if not pages:
            raise EmptyDocument()
        return tuple(pages)

    @classmethod
    def _chunk_pages(
        cls,
        pages: tuple[ExtractedPage, ...],
        chunk_size: int,
        overlap: int,
    ) -> list[tuple[int, str]]:
        if chunk_size <= 0 or overlap < 0 or overlap >= chunk_size:
            raise ChunkingFailed("청킹 설정값이 올바르지 않습니다.")

        chunks: list[tuple[int, str]] = []
        for page in pages:
            for section in cls._split_clause_sections(page.text):
                chunks.extend(
                    (page.page_number, chunk)
                    for chunk in cls._split_section(section, chunk_size, overlap)
                )

        if not chunks:
            raise ChunkingFailed("생성된 문서 청크가 없습니다.")
        return chunks

    @classmethod
    def _split_clause_sections(cls, text: str) -> list[str]:
        normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
        if not normalized:
            return []

        starts = [match.start() for match in cls._CLAUSE_HEADING.finditer(normalized)]
        if not starts:
            return [normalized]

        boundaries = [0] if starts[0] > 0 else []
        boundaries.extend(starts)
        boundaries.append(len(normalized))
        sections = [
            normalized[boundaries[index]:boundaries[index + 1]].strip()
            for index in range(len(boundaries) - 1)
        ]
        return [section for section in sections if section]

    @classmethod
    def _split_section(cls, section: str, chunk_size: int, overlap: int) -> list[str]:
        if len(section) <= chunk_size:
            return [section]

        paragraphs = [
            paragraph.strip()
            for paragraph in cls._PARAGRAPH_BREAK.split(section)
            if paragraph.strip()
        ]
        chunks: list[str] = []
        current = ""

        for paragraph in paragraphs:
            if len(paragraph) > chunk_size:
                if current:
                    chunks.append(current)
                    current = ""
                chunks.extend(cls._split_long_text(paragraph, chunk_size, overlap))
                continue

            candidate = paragraph if not current else f"{current}\n\n{paragraph}"
            if len(candidate) <= chunk_size:
                current = candidate
            else:
                chunks.append(current)
                current = paragraph

        if current:
            chunks.append(current)
        return chunks

    @staticmethod
    def _split_long_text(text: str, chunk_size: int, overlap: int) -> list[str]:
        chunks: list[str] = []
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            chunk = text[start:end].strip()
            if chunk:
                chunks.append(chunk)
            if end == len(text):
                break
            start = end - overlap
        return chunks

    @classmethod
    def _chunk_text(cls, text: str, chunk_size: int, overlap: int) -> list[str]:
        """기존 호출부와 단위 테스트를 위한 단일 페이지 호환 진입점입니다."""
        return [
            chunk
            for _page_number, chunk in cls._chunk_pages(
                (ExtractedPage(page_number=1, text=text),),
                chunk_size,
                overlap,
            )
        ]
