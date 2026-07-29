from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import Field

from app.schemas.common import ApiModel, ProcessingStatus
# ExtractionRequest 재노출: surin models/extraction_inference.py가 app.schemas.analyze에서 import한다.
from app.schemas.extraction import ExtractionResult, ExtractionRequest
from app.schemas.classification import ClassificationResult
from app.schemas.severity import SeverityResult


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
    rainfall_24h_mm: Optional[float] = Field(default=None, ge=0)
    gdacs_alert_level: Optional[int] = Field(default=None, ge=0, le=2)
    actor1_type: Optional[str] = None
    actor2_type: Optional[str] = None
    stock_volatility_20d: Optional[float] = Field(default=None, ge=0)
    # [surin F3] severity_engine(v0.2-realtime) 입력용 override 필드
    bdi_index: Optional[float] = None
    tone_score: Optional[float] = None
    is_supply_chain_relevant: Optional[bool] = None


class AnalyzeRequest(ApiModel):
    event: EventInput
    options: AnalyzeOptions = AnalyzeOptions()
    feature_overrides: Optional[FeatureOverrides] = None
    # [surin F4] 호출자가 이미 /internal/llm/extract로 추출을 마쳤다면 그 결과를 그대로 전달해
    # analyze() 내부에서 같은 뉴스에 대해 LLM 추출을 중복 호출하지 않도록 한다.
    extraction_override: Optional[ExtractionResult] = None


class FeatureVector(ApiModel):
    # merge 필드(기존 필수) — neutral/부분 override 생성이 안전하도록 기본값 부여
    goldstein_scale: float = 0.0
    news_count: int = 0
    rainfall_24h_mm: float = 0.0
    gdacs_alert_level: int = 0
    actor1_type: str = ""
    actor2_type: str = ""
    stock_volatility_20d: float = 0.0
    # [surin F3] severity_engine(v0.2-realtime)이 사용하는 필드
    bdi_index: float = 0.0
    tone_score: float = 0.0
    is_supply_chain_relevant: bool = True


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
    # [surin F9] Spring AnalysisService가 top-level affected_materials로 material_category를 뽑아 F9를 트리거한다.
    affected_materials: list[str] = []
    briefing_id: Optional[int] = None
    erp_context_included: bool = False
    contract_rag_included: bool = False
    feature_enrichment_applied: bool = False
    mock: bool = False
    mock_reason: Optional[str] = None
    processed_at: datetime
