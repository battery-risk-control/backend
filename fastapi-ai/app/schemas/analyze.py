from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import Field

from app.schemas.common import ApiModel, ImpactDomain, ProcessingStatus, Severity


class EventInput(ApiModel):
    external_event_id: str
    title: str
    content: str
    source_name: str
    source_url: Optional[str] = None
    published_at: datetime
    country_code: Optional[str] = Field(default=None, min_length=2, max_length=2)


class AnalyzeOptions(ApiModel):
    enrich_features: bool = True
    include_erp_context: bool = True
    include_contract_rag: bool = True
    generate_briefing: bool = True


class FeatureOverrides(ApiModel):
    goldstein_scale: Optional[float] = None
    news_count: Optional[int] = Field(default=None, ge=0)
    country_is_mining_hub: Optional[bool] = None
    rainfall_24h_mm: Optional[float] = Field(default=None, ge=0)
    gdacs_alert_level: Optional[int] = Field(default=None, ge=0, le=2)
    actor1_type: Optional[str] = None
    actor2_type: Optional[str] = None
    stock_volatility_20d: Optional[float] = Field(default=None, ge=0)


class AnalyzeRequest(ApiModel):
    event: EventInput
    options: AnalyzeOptions = AnalyzeOptions()
    feature_overrides: Optional[FeatureOverrides] = None


class ExtractionResult(ApiModel):
    country_code: str
    affected_materials: list[str]
    event_type: str
    tone_score: float = Field(ge=-1.0, le=1.0)
    impact_domain_draft: ImpactDomain
    summary_kr: str


class FeatureVector(ApiModel):
    goldstein_scale: float
    news_count: int
    country_is_mining_hub: bool
    rainfall_24h_mm: float
    gdacs_alert_level: int
    actor1_type: str
    actor2_type: str
    stock_volatility_20d: float


class ClassificationResult(ApiModel):
    impact_domain: ImpactDomain
    confidence: float = Field(ge=0.0, le=1.0)
    model_version: str


class SeverityResult(ApiModel):
    severity: Severity
    score: float = Field(ge=0.0, le=100.0)
    reason_codes: list[str]
    rule_version: str


class MatchedEntities(ApiModel):
    material_id: int
    supplier_ids: list[int]
    contract_ids: list[int]


class AnalyzeResponseData(ApiModel):
    analysis_id: str
    status: ProcessingStatus
    extraction: ExtractionResult
    features: FeatureVector
    classification: ClassificationResult
    severity: SeverityResult
    matched_entities: MatchedEntities
    briefing_id: Optional[int] = None
    mock: bool = False
    mock_reason: Optional[str] = None
    processed_at: datetime
