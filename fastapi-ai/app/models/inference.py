from dataclasses import dataclass
from typing import Protocol

from app.core.config import get_settings
from app.schemas.analyze import FeatureVector
from app.schemas.common import ImpactDomain
from app.models.classifier import classify_impact_domain


@dataclass(frozen=True)
class Prediction:
    impact_domain: ImpactDomain
    confidence: float
    model_version: str
    mock: bool


class ModelInference(Protocol):
    def predict(self, features: FeatureVector) -> Prediction: ...


class MockModelInference:
    """Stable substitute until the trained XGBoost artifact is connected."""

    def predict(self, _features: FeatureVector) -> Prediction:
        settings = get_settings()
        result = classify_impact_domain(_features)
        return Prediction(
            impact_domain=result.impact_domain,
            confidence=result.confidence,
            model_version=settings.model_version,
            mock=True,
        )
