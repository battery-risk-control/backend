from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, UploadFile

from app.api.dependencies import get_rag_service
from app.core.exceptions import RagFilterRequired, UnsupportedDocumentType
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.rag import ContractUploadResult, RagSearchItem, RagSearchRequest, RagSearchResult
from app.services.rag_service import RagService

router = APIRouter(prefix="/api/v1/rag", tags=["rag"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}, 503: {"model": ApiErrorResponse}}


@router.post("/contracts", response_model=ApiResponse[ContractUploadResult], responses=ERRORS)
async def upload_contract(
    file: Annotated[UploadFile, File()],
    contract_id: Annotated[int, Form(alias="contractId")],
    supplier_id: Annotated[int, Form(alias="supplierId")],
    material_id: Annotated[int, Form(alias="materialId")],
    document_type: Annotated[str, Form(alias="documentType")] = "LTA",
    service: RagService = Depends(get_rag_service),
) -> ApiResponse[ContractUploadResult]:
    try:
        summary = service.upload(
            await file.read(), file.filename or "contract.pdf", contract_id,
            supplier_id, material_id, document_type,
        )
    except ValueError as exception:
        raise UnsupportedDocumentType(file.filename or "unknown") from exception
    return ApiResponse(data=ContractUploadResult(
        document_id=summary.document_id, contract_id=contract_id,
        file_name=summary.file_name, chunk_count=summary.chunk_count,
        embedding_status="COMPLETED",
        metadata={"supplierId": supplier_id, "materialId": material_id, "documentType": document_type},
        mock=True,
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
    )
    results = [RagSearchItem(
        document_id=item.document_id, contract_id=item.contract_id,
        supplier_id=item.supplier_id, material_id=item.material_id,
        content=item.chunk.content, similarity_score=0.92,
        page_number=item.chunk.page_number,
    ) for item in chunks if request.filters.material_id is None or item.material_id == request.filters.material_id]
    return ApiResponse(data=RagSearchResult(results=results, mock=True))
