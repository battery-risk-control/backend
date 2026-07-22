from app.schemas.common import ApiModel


class DocumentProcessResult(ApiModel):
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    file_name: str
    content_hash: str
    chunk_count: int
    processing_status: str = "COMPLETED"
    duplicate: bool = False
    mock: bool = True
