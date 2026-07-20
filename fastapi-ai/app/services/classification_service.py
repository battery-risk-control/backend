from app.ml.inference import MockModelInference, ModelInference
from app.schemas.analyze import ClassificationResult, FeatureVector


class ClassificationService:
    def __init__(self, inference: ModelInference | None = None) -> None:
        self._inference = inference or MockModelInference()

    def classify(self, features: FeatureVector) -> ClassificationResult:
        prediction = self._inference.predict(features)
        return ClassificationResult(
            impact_domain=prediction.impact_domain,
            confidence=prediction.confidence,
            model_version=prediction.model_version,
        )
