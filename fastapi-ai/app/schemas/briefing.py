from pydantic import Field

from app.schemas.common import ApiModel, ProcessingStatus


class BriefingGenerationRequest(ApiModel):
    risk_id: int
    event_summary: str = "Mock event summary"
    inventory_summary: str = "Mock inventory summary"
    contract_summary: str = "Mock contract summary"


class BriefingGenerationResult(ApiModel):
    briefing_id: int
    risk_id: int
    status: ProcessingStatus
    headline: str
    event_summary: str
    inventory_perspective: str
    contract_perspective: str
    recommended_actions: list[str]
    warnings: list[str] = Field(default_factory=list)
    mock: bool = True


# --- S13 템플릿 브리핑 (ERP·Severity·RAG·대체공급사 구조화 입력 → 결정적 한국어 조립) ---


class ContractEvidenceItem(ApiModel):
    document_id: str
    contract_id: int
    page_number: int
    content: str
    similarity_score: float


class AlternativeSupplierItem(ApiModel):
    erp_supplier_id: str
    supplier_name: str
    approved_status: str
    feoc_status: str
    iatf_16949_certified: bool
    ppap_approved: bool
    lead_time_days: int | None = None


class BriefingComposeRequest(ApiModel):
    erp_material_id: str
    material_name: str
    unit: str
    severity: str
    score: float
    reason_codes: list[str] = Field(default_factory=list)
    inventory_days: float | None = None
    safety_stock_days: float | None = None
    available_quantity: float | None = None
    expected_supply_gap_days: float | None = None
    next_eta_days: int | None = None
    supplier_dependency_ratio: float | None = None
    primary_supplier_id: str
    primary_supplier_status: str
    feoc_status: str
    data_quality_status: str
    contract_evidence: list[ContractEvidenceItem] = Field(default_factory=list)
    alternative_suppliers: list[AlternativeSupplierItem] = Field(default_factory=list)
    mock: bool = True


class BriefingComposeResult(ApiModel):
    headline: str
    risk_summary: str
    inventory_summary: str
    supplier_dependency_summary: str
    supply_gap_summary: str
    contract_evidence_summary: str
    alternative_supplier_summary: str
    recommended_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    template_version: str = "briefing-template-v1"
    mock: bool = True
