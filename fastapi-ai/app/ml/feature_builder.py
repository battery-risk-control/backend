from app.schemas.analyze import FeatureOverrides, FeatureVector

DEFAULT_FEATURES = {
    "goldstein_scale": -7.2,
    "news_count": 15,
    "country_is_mining_hub": True,
    "rainfall_24h_mm": 230.0,
    "gdacs_alert_level": 2,
    "actor1_type": "GOV",
    "actor2_type": "COM",
    "stock_volatility_20d": 0.041,
}

def build_feature_vector(overrides: FeatureOverrides | None = None) -> FeatureVector:
    values = DEFAULT_FEATURES.copy()
    if overrides:
        for key, value in overrides.model_dump().items():
            if value is not None:
                values[key] = value
    return FeatureVector(**values)
