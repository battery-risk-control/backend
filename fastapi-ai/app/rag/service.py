"""Backward-compatible functional facade over the service-layer RAG boundary."""

from app.services.rag_service import rag_service


def ingest_contract(
    file_name: str,
    content: str,
    contract_id: int,
    supplier_id: int,
    material_id: int,
    document_type: str,
) -> int:
    summary = rag_service.upload(
        content.encode("utf-8"), file_name, contract_id,
        supplier_id, material_id, document_type,
    )
    return summary.chunk_count
