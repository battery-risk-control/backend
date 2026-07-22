from app.schemas.common import ApiModel


class DocumentChunkResult(ApiModel):
    document_id: str
    chunk_index: int
    page_number: int
    content: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    content_hash: str


class DocumentProcessResult(ApiModel):
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    file_name: str
    content_hash: str
    chunk_count: int
    chunks: list[DocumentChunkResult]
    embedding_type: str
    embedding_version: str
    mock_embedding: bool
    processing_status: str = "COMPLETED"
    duplicate: bool = False
    mock: bool = True
