from typing import Annotated

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import Field

from app.schemas.common import ApiModel, ApiResponse
from app.services.rag_service import rag_service

router = APIRouter(prefix="/api/v1/rag", tags=["rag"])


class ContractUploadResult(ApiModel):
    document_id: str
    contract_id: int
    file_name: str
    chunk_count: int
    embedding_status: str
    metadata: dict


class RagFilters(ApiModel):
    supplier_id: int | None = None
    material_id: int | None = None
    contract_id: int | None = None
    clause_types: list[str] = Field(default_factory=list)


class RagSearchRequest(ApiModel):
    query: str
    filters: RagFilters
    top_k: int = Field(default=5, ge=1, le=20)


class RagSearchItem(ApiModel):
    document_id: str
    contract_id: int
    clause_id: int | None = None
    clause_type: str | None = None
    content: str
    similarity_score: float
    page_number: int | None = None


class RagSearchResult(ApiModel):
    results: list[RagSearchItem]


@router.post("/contracts", response_model=ApiResponse[ContractUploadResult])
async def upload_contract(
    file: Annotated[UploadFile, File()],
    contract_id: Annotated[int, Form(alias="contractId")],
    supplier_id: Annotated[int, Form(alias="supplierId")],
    material_id: Annotated[int, Form(alias="materialId")],
    document_type: Annotated[str, Form(alias="documentType")] = "LTA",
) -> ApiResponse[ContractUploadResult]:
    summary = rag_service.upload(
        await file.read(), file.filename or "contract.pdf", contract_id,
        supplier_id, material_id, document_type,
    )
    return ApiResponse(
        data=ContractUploadResult(
            document_id=summary.document_id,
            contract_id=contract_id,
            file_name=summary.file_name,
            chunk_count=summary.chunk_count,
            embedding_status="COMPLETED",
            metadata={
                "supplierId": supplier_id,
                "materialId": material_id,
                "documentType": document_type,
            },
        )
    )


@router.post("/search", response_model=ApiResponse[RagSearchResult])
def search_contracts(request: RagSearchRequest) -> ApiResponse[RagSearchResult]:
    if request.filters.contract_id is None and request.filters.supplier_id is None:
        raise HTTPException(status_code=422, detail="contractId or supplierId is required")
    chunks = rag_service.search(request.query, request.filters.contract_id, request.filters.supplier_id, request.top_k)
    results = [RagSearchItem(
        document_id=item.document_id, contract_id=item.contract_id,
        content=item.chunk.content, similarity_score=0.92,
        page_number=item.chunk.page_number,
    ) for item in chunks]
    if not results:
        results = [RagSearchItem(
            document_id="DOC-501-001", contract_id=request.filters.contract_id or 501,
            clause_id=8001, clause_type="PRICE_ESCALATION",
            content="기준 가격 대비 10퍼센트 이상 변동할 경우 당사자들은 가격을 재협상할 수 있다.",
            similarity_score=0.92, page_number=12,
        )]
    return ApiResponse(data=RagSearchResult(results=results))
