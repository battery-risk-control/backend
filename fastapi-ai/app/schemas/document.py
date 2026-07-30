from app.schemas.common import ApiModel


class DocumentChunkResult(ApiModel):
    document_id: str
    chunk_index: int
    page_number: int
    content: str
    contract_id: int
    document_type: str
    content_hash: str
    # 인바운드(공급사 계약)는 supplier_id+material_id, 아웃바운드(고객사 계약)는
    # product_id+customer_id — 최소 한 쌍은 채워진다(둘 다 optional인 이유).
    supplier_id: int | None = None
    material_id: int | None = None
    product_id: int | None = None
    customer_id: int | None = None


class DocumentProcessResult(ApiModel):
    document_id: str
    contract_id: int
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
    supplier_id: int | None = None
    material_id: int | None = None
    product_id: int | None = None
    customer_id: int | None = None
