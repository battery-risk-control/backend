from app.schemas.analyze import ClassificationResult, FeatureVector
from app.schemas.common import ImpactDomain

MODEL_VERSION = "xgboost-impact-domain-v0.1-mock"

def classify_impact_domain(features: FeatureVector) -> ClassificationResult:
    if features.gdacs_alert_level >= 2 or features.rainfall_24h_mm >= 100:
        domain, confidence = ImpactDomain.PRODUCTION, 0.91
    elif features.actor1_type == "LAB":
        domain, confidence = ImpactDomain.LOGISTICS, 0.82
    elif features.goldstein_scale <= -5:
        domain, confidence = ImpactDomain.GEOPOLITICS, 0.78
    else:
        domain, confidence = ImpactDomain.MARKET, 0.70
    return ClassificationResult(
        impact_domain=domain,
        confidence=confidence,
        model_version=MODEL_VERSION,
    )
