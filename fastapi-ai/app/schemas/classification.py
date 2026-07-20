from pydantic import Field

from app.schemas.common import ApiModel, ImpactDomain


class ClassificationResult(ApiModel):
    impact_domain: ImpactDomain
    confidence: float = Field(ge=0.0, le=1.0)
    model_version: str
    mock: bool = True
