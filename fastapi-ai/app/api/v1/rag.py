from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, UploadFile

from app.api.dependencies import get_rag_service
from app.core.exceptions import RagFilterRequired
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.rag import ContractUploadResult, RagSearchItem, RagSearchRequest, RagSearchResult
from app.services.rag_service import RagService

router = APIRouter(prefix="/api/v1/rag", tags=["rag"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}, 503: {"model": ApiErrorResponse}}


@router.post("/contracts", response_model=ApiResponse[ContractUploadResult], responses=ERRORS)
async def upload_contract(
    file: Annotated[UploadFile, File()],
    contract_id: Annotated[int, Form()],
    supplier_id: Annotated[int, Form()],
    material_id: Annotated[int, Form()],
    document_type: Annotated[str, Form()] = "LTA",
    service: RagService = Depends(get_rag_service),
) -> ApiResponse[ContractUploadResult]:
    summary = service.upload(
        await file.read(), file.filename or "contract.pdf", contract_id,
        supplier_id, material_id, document_type,
    )
    return ApiResponse(data=ContractUploadResult(
        document_id=summary.document_id, contract_id=contract_id,
        file_name=summary.file_name, chunk_count=summary.chunk_count,
        embedding_status="COMPLETED",
        metadata={
            "supplier_id": supplier_id,
            "material_id": material_id,
            "document_type": document_type,
            "embedding_type": summary.embedding_type,
            "embedding_version": summary.embedding_version,
            "mock_embedding": summary.mock_embedding,
        },
        mock=summary.mock_embedding,
    ))


@router.post("/search", response_model=ApiResponse[RagSearchResult], responses=ERRORS)
def search_contracts(
    request: RagSearchRequest,
    service: RagService = Depends(get_rag_service),
) -> ApiResponse[RagSearchResult]:
    if request.filters.contract_id is None and request.filters.supplier_id is None:
        raise RagFilterRequired()
    chunks = service.search(
        request.query, request.filters.contract_id,
        request.filters.supplier_id, request.top_k,
        request.filters.material_id,
    )
    results = [RagSearchItem(
        document_id=item.document_id, contract_id=item.contract_id,
        supplier_id=item.supplier_id, material_id=item.material_id,
        document_type=item.document_type, chunk_index=item.chunk_index,
        content=item.content, content_hash=item.content_hash,
        similarity_score=item.similarity_score,
        page_number=item.page_number,
        embedding_type=item.embedding_type,
        embedding_version=item.embedding_version,
        mock_embedding=item.mock_embedding,
    ) for item in chunks]
    return ApiResponse(data=RagSearchResult(results=results, mock=service.mock_embedding))
