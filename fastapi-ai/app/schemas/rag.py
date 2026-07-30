from pydantic import Field

from app.schemas.common import ApiModel


class ContractUploadResult(ApiModel):
    document_id: str
    contract_id: int
    file_name: str
    chunk_count: int
    embedding_status: str
    metadata: dict[str, object]
    mock: bool = True


class RagFilters(ApiModel):
    supplier_id: int | None = None
    material_id: int | None = None
    contract_id: int | None = None
    clause_types: list[str] = Field(default_factory=list)


class RagSearchRequest(ApiModel):
    query: str = Field(min_length=1)
    filters: RagFilters
    top_k: int = Field(default=5, ge=1, le=20)


class RagSearchItem(ApiModel):
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    document_type: str
    chunk_index: int
    clause_id: int | None = None
    clause_type: str | None = None
    content: str
    content_hash: str
    similarity_score: float
    page_number: int
    embedding_type: str
    embedding_version: str
    mock_embedding: bool


class RagSearchResult(ApiModel):
    results: list[RagSearchItem]
    mock: bool = True
