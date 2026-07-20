from pydantic import Field

from app.schemas.common import ApiModel, ImpactDomain


class ExtractionRequest(ApiModel):
    title: str
    content: str
    country_code: str | None = Field(default=None, min_length=2, max_length=2)


class ExtractionResult(ApiModel):
    country_code: str
    affected_materials: list[str]
    event_type: str
    tone_score: float = Field(ge=-1.0, le=1.0)
    impact_domain_draft: ImpactDomain
    summary_kr: str
    mock: bool = True
