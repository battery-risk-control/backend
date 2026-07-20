from app.schemas.analyze import FeatureOverrides, FeatureVector
from app.ml.feature_builder import build_feature_vector


class FeatureService:
    def build(self, overrides: FeatureOverrides | None) -> FeatureVector:
        return build_feature_vector(overrides)
