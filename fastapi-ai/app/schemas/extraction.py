from pydantic import Field

from app.schemas.common import ApiModel, ImpactDomain


class ExtractionRequest(ApiModel):
    title: str
    content: str
    country_code: str | None = Field(default=None, min_length=2, max_length=2)


class ExtractionResult(ApiModel):
    # [surin 병합] surin extraction은 country_code를 세팅하지 않으므로 optional로 완화(미완화 시 ValidationError)
    country_code: str | None = None
    affected_materials: list[str]
    event_type: str
    tone_score: float = Field(ge=-1.0, le=1.0)
    impact_domain_draft: ImpactDomain  # ImpactDomain enum은 IRRELEVANT 포함 → surin LLM draft 전 값 수용
    summary_kr: str
    # [surin F3] severity_engine relevance gate + 추출 모델 버전
    is_supply_chain_relevant: bool = True
    extraction_model_version: str = "llm-extraction-v0.1-mock"
    mock: bool = True
