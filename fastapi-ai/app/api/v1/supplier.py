from fastapi import APIRouter, Depends

from app.api.dependencies import get_supplier_recommendation_service
from app.schemas.common import ApiErrorResponse, ApiResponse
from app.schemas.supplier import SupplierRecommendationRequest, SupplierRecommendationResult
from app.services.supplier_recommendation_service import SupplierRecommendationService

router = APIRouter(prefix="/api/v1/suppliers", tags=["suppliers"])
ERRORS = {422: {"model": ApiErrorResponse}, 500: {"model": ApiErrorResponse}}


@router.post("/recommend", response_model=ApiResponse[SupplierRecommendationResult], responses=ERRORS)
def recommend_suppliers(
    request: SupplierRecommendationRequest,
    service: SupplierRecommendationService = Depends(get_supplier_recommendation_service),
) -> ApiResponse[SupplierRecommendationResult]:
    return ApiResponse(data=service.recommend(
        request.material_category, request.risk_note, request.erp_alternative_supplier_risk_score,
        request.erp_supplier_risk_scores,
    ))
