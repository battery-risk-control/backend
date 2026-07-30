from pydantic import field_validator

from app.schemas.analyze import FeatureVector
from app.schemas.briefing import BriefingGenerationRequest, BriefingGenerationResult
from app.schemas.common import ApiModel
from app.schemas.extraction import ExtractionRequest
from app.schemas.severity import SeverityInput


LlmExtractRequest = ExtractionRequest
BriefingGenerateRequest = BriefingGenerationRequest
BriefingGenerateResult = BriefingGenerationResult


class MlClassifyRequest(ApiModel):
    features: FeatureVector | None = None

    @field_validator("features", mode="before")
    @classmethod
    def empty_features_use_mock_defaults(cls, value):
        return None if value == {} else value


SeverityScoreRequest = SeverityInput
