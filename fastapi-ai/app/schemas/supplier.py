from app.schemas.common import ApiModel


class SupplierCandidate(ApiModel):
    """Spring이 필수조건을 통과한 후보만 걸러서 보낸 정보입니다. FastAPI는 이 목록 밖의 후보를 만들지 않습니다."""

    supplier_id: int
    supplier_code: str
    supplier_name: str
    country_code: str | None = None
    feoc_status: str
    certifications: str | None = None
    risk_level: str
    supplier_status: str
    lead_time_days: int | None = None
    minimum_order_quantity: float | None = None
    supply_share_ratio: float | None = None
    priority_rank: int | None = None
    is_alternative: bool = False
    latest_unit_price: float | None = None
    latest_order_date: str | None = None


class SupplierRecommendationRequest(ApiModel):
    material_category: str
    risk_note: str | None = None
    erp_alternative_supplier_risk_score: float | None = None
    erp_supplier_risk_scores: dict[str, float] | None = None


class RankedSupplierRecommendation(ApiModel):
    supplier_id: int
    supplier_code: str
    supplier_name: str
    rank: int
    pros: list[str]
    cons: list[str]
    recommendation_reason: str


class SupplierRecommendationResult(ApiModel):
    material_category: str
    recommendations: list[RankedSupplierRecommendation]
    caveats: list[str]
    mock: bool = True
