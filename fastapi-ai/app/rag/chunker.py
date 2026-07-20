from dataclasses import dataclass

from app.core.config import get_settings


@dataclass(frozen=True)
class DocumentChunk:
    content: str
    index: int
    page_number: int | None = None


class ContractChunker:
    def chunk(self, text: str) -> list[DocumentChunk]:
        settings = get_settings()
        if not text.strip():
            return [DocumentChunk(content="Mock PDF content pending parser integration", index=0, page_number=1)]
        step = max(1, settings.chunk_size - settings.chunk_overlap)
        return [
            DocumentChunk(content=text[start:start + settings.chunk_size], index=index)
            for index, start in enumerate(range(0, len(text), step))
        ]
