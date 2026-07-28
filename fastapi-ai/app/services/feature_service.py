from app.schemas.analyze import FeatureOverrides, FeatureVector
from app.models.feature_builder import build_feature_vector


class FeatureService:
    def build(self, overrides: FeatureOverrides | None, enrich_features: bool = True) -> FeatureVector:
        if enrich_features:
            return build_feature_vector(overrides)
        neutral = FeatureVector(
            goldstein_scale=0.0, news_count=0, country_is_mining_hub=False,
            rainfall_24h_mm=0.0, gdacs_alert_level=0,
            actor1_type="UNKNOWN", actor2_type="UNKNOWN", stock_volatility_20d=0.0,
            # [surin F3] severity_engine 입력 필드
            bdi_index=0.0, tone_score=0.0, is_supply_chain_relevant=True,
        )
        if not overrides:
            return neutral
        values = neutral.model_dump()
        values.update({key: value for key, value in overrides.model_dump().items() if value is not None})
        return FeatureVector(**values)
