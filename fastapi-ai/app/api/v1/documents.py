from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, UploadFile
from pydantic import BaseModel

from app.api.dependencies import get_document_service
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.document import DocumentChunkResult, DocumentProcessResult
from app.services.document_service import DocumentService

router = APIRouter(prefix="/api/v1/documents", tags=["documents"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}}


class ExtractTextResult(BaseModel):
    text: str


@router.post(
    "/extract-text",
    response_model=ApiResponse[ExtractTextResult],
    responses=ERRORS,
)
async def extract_text(
    file: Annotated[UploadFile, File(description="PDF 또는 UTF-8 TXT 문서")],
    service: DocumentService = Depends(get_document_service),
) -> ApiResponse[ExtractTextResult]:
    """청킹/임베딩 없이 원문 텍스트만 반환한다.

    Spring의 계약서 업로드 미리보기(정규식 필드 자동추출)가 이 텍스트를 갖고 계약번호/계약명/
    발효일/만료일을 뽑는다 — ChromaDB에는 아무것도 안 씀, `/process`와 별개 경로.
    """
    text = service.extract_text(await file.read(), file.filename or "document")
    return ApiResponse(data=ExtractTextResult(text=text))


@router.post("/process", response_model=ApiResponse[DocumentProcessResult], responses=ERRORS)
async def process_document(
    file: Annotated[UploadFile, File(description="PDF 또는 UTF-8 TXT 문서")],
    document_id: Annotated[str, Form(min_length=1)],
    contract_id: Annotated[int, Form(gt=0)],
    supplier_id: Annotated[int, Form(gt=0)],
    material_id: Annotated[int, Form(gt=0)],
    document_type: Annotated[str, Form()] = "LTA",
    force_reprocess: Annotated[bool, Form()] = False,
    service: DocumentService = Depends(get_document_service),
) -> ApiResponse[DocumentProcessResult]:
    document, duplicate = service.process(
        await file.read(), file.filename or "document", contract_id,
        supplier_id, material_id, document_type, document_id=document_id,
        force_reprocess=force_reprocess,
    )

    return ApiResponse(data=DocumentProcessResult(
        document_id=document.document_id,
        contract_id=document.contract_id,
        supplier_id=document.supplier_id,
        material_id=document.material_id,
        document_type=document.document_type,
        file_name=document.file_name,
        content_hash=document.content_hash,
        chunk_count=len(document.chunks),
        chunks=[
            DocumentChunkResult(
                document_id=chunk.document_id,
                chunk_index=chunk.chunk_index,
                page_number=chunk.page_number,
                content=chunk.content,
                contract_id=chunk.contract_id,
                supplier_id=chunk.supplier_id,
                material_id=chunk.material_id,
                document_type=chunk.document_type,
                content_hash=chunk.content_hash,
            )
            for chunk in document.chunks
        ],
        embedding_type=document.embedding_type,
        embedding_version=document.embedding_version,
        mock_embedding=document.mock_embedding,
        duplicate=duplicate,
    ))
