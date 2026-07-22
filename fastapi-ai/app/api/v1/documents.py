from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, UploadFile

from app.api.dependencies import get_document_service
from app.core.exceptions import UnsupportedDocumentType
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.document import DocumentProcessResult
from app.services.document_service import DocumentService, EmptyDocumentError, UnsupportedDocumentError

router = APIRouter(prefix="/api/v1/documents", tags=["documents"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}}


@router.post("/process", response_model=ApiResponse[DocumentProcessResult], responses=ERRORS)
async def process_document(
    file: Annotated[UploadFile, File(description="PDF 또는 UTF-8 TXT 문서")],
    document_id: Annotated[str, Form(min_length=1)],
    contract_id: Annotated[int, Form(gt=0)],
    supplier_id: Annotated[int, Form(gt=0)],
    material_id: Annotated[int, Form(gt=0)],
    document_type: Annotated[str, Form()] = "LTA",
    service: DocumentService = Depends(get_document_service),
) -> ApiResponse[DocumentProcessResult]:
    try:
        document, duplicate = service.process(
            await file.read(), file.filename or "document", contract_id,
            supplier_id, material_id, document_type, document_id=document_id,
        )
    except EmptyDocumentError as exception:
        raise UnsupportedDocumentType(file.filename or "unknown") from exception
    except UnsupportedDocumentError as exception:
        raise UnsupportedDocumentType(file.filename or "unknown") from exception

    return ApiResponse(data=DocumentProcessResult(
        document_id=document.document_id,
        contract_id=document.contract_id,
        supplier_id=document.supplier_id,
        material_id=document.material_id,
        document_type=document.document_type,
        file_name=document.file_name,
        content_hash=document.content_hash,
        chunk_count=len(document.chunks),
        duplicate=duplicate,
    ))
