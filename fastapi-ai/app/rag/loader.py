from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class LoadedDocument:
    text: str
    file_name: str


class DocumentLoader:
    """Lightweight placeholder. PDF extraction will replace byte decoding later."""

    def load(self, content: bytes, file_name: str) -> LoadedDocument:
        suffix = Path(file_name).suffix.lower()
        if suffix not in {".pdf", ".txt"}:
            raise ValueError("only PDF and TXT documents are supported")
        text = content.decode("utf-8", errors="ignore") if suffix == ".txt" else ""
        return LoadedDocument(text=text, file_name=file_name)
