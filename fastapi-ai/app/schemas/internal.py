from app.schemas.analyze import FeatureVector
from app.schemas.common import ApiModel
from pydantic import field_validator

class LlmExtractRequest(ApiModel):
    title: str
    content: str
    country_code: str | None = None

class MlClassifyRequest(ApiModel):
    features: FeatureVector | None = None

    @field_validator("features", mode="before")
    @classmethod
    def empty_features_use_mock_defaults(cls, value):
        return None if value == {} else value

class SeverityScoreRequest(ApiModel):
    features: FeatureVector | None = None
    stock_days: int | None = None
    feoc_status: bool | None = None

    @field_validator("features", mode="before")
    @classmethod
    def empty_features_use_mock_defaults(cls, value):
        return None if value == {} else value

class BriefingGenerateRequest(ApiModel):
    risk_id: int
    event_summary: str = "Mock event summary"
    inventory_summary: str = "Mock inventory summary"
    contract_summary: str = "Mock contract summary"

class BriefingGenerateResult(ApiModel):
    headline: str
    event_summary: str
    inventory_perspective: str
    contract_perspective: str
    recommended_actions: list[str]
    warnings: list[str]
