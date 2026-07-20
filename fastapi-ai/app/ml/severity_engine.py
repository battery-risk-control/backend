import math
from app.schemas.analyze import FeatureVector, SeverityResult
from app.schemas.common import Severity

RULE_VERSION = "severity-rule-v0.1"

def score_severity(features: FeatureVector, stock_days: int | None = None, feoc_status: bool | None = None) -> SeverityResult:
    score = (
        abs(features.goldstein_scale) * 4
        + min(features.stock_volatility_20d * 500, 15)
        + math.log(features.news_count + 1) * 5
        + features.gdacs_alert_level * 15
        + (10 if features.rainfall_24h_mm >= 100 else 0)
    )
    reasons = []
    if features.gdacs_alert_level >= 2:
        reasons.append("GDACS_RED_ALERT")
    if features.rainfall_24h_mm >= 100:
        reasons.append("RAINFALL_THRESHOLD_EXCEEDED")
    if features.goldstein_scale <= -5:
        reasons.append("NEGATIVE_GOLDSTEIN_SCALE")
    if stock_days is not None and stock_days < 20:
        score += 15
        reasons.append("LOW_STOCK_COVERAGE")
    if feoc_status is True:
        score = 100
        reasons.append("FEOC_VIOLATION")
    score = round(min(score, 100), 1)
    severity = Severity.CRITICAL if score >= 70 else Severity.WARNING if score >= 40 else Severity.NORMAL
    return SeverityResult(severity=severity, score=score, reason_codes=reasons, rule_version=RULE_VERSION)
