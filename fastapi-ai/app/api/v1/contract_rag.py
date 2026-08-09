from fastapi import APIRouter, Depends

from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.contract_rag import (
    ContractRagSearchItem,
    ContractRagSearchRequest,
    ContractRagSearchResult,
)
from app.services.contract_rag_service import (
    ContractRagSearchService,
    get_contract_rag_search_service,
)

router = APIRouter(prefix="/api/v1/contract-rag", tags=["contract-rag"])
ERRORS = {
    422: {"model": ApiErrorResponse},
    500: {"model": ApiErrorResponse},
    503: {"model": ApiErrorResponse},
}


@router.post("/search", response_model=ApiResponse[ContractRagSearchResult], responses=ERRORS)
def search_clauses(
    request: ContractRagSearchRequest,
    service: ContractRagSearchService = Depends(get_contract_rag_search_service),
) -> ApiResponse[ContractRagSearchResult]:
    """계약 조항 의미검색. Spring `/api/v1/contract-rag/search`만 호출한다.

    필터 없이 부르면 컬렉션 전체(모든 계약)를 훑는다 — 화면이 검색창에 단어만 넣고
    조항을 찾는 흐름이기 때문이다. 멀티에이전트가 쓰는 `/api/v1/rag/search`는 기존
    규칙(필터 필수)을 그대로 유지하므로 이 엔드포인트와 서로 영향이 없다.
    """
    hits = service.search(
        request.query,
        kind=request.kind,
        contract_id=request.contract_id,
        supplier_id=request.supplier_id,
        material_id=request.material_id,
        product_id=request.product_id,
        customer_id=request.customer_id,
        top_k=request.top_k,
    )
    narrowed = (
        request.kind != "ALL"
        or request.contract_id is not None
        or request.product_id is not None
    )
    return ApiResponse(data=ContractRagSearchResult(
        results=[
            ContractRagSearchItem(
                document_id=hit.document_id,
                contract_id=hit.contract_id,
                document_type=hit.document_type,
                chunk_index=hit.chunk_index,
                page_number=hit.page_number,
                content=hit.content,
                content_hash=hit.content_hash,
                similarity_score=hit.similarity_score,
                embedding_type=service.embedding_type,
                embedding_version=service.embedding_version,
                mock_embedding=service.mock_embedding,
                supplier_id=hit.supplier_id,
                material_id=hit.material_id,
                product_id=hit.product_id,
                customer_id=hit.customer_id,
            )
            for hit in hits
        ],
        scope="filtered" if narrowed else "all",
        mock=service.mock_embedding,
        embedding_type=service.embedding_type,
        embedding_version=service.embedding_version,
        collection_name=service.collection_name,
    ))
