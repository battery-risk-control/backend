from app.core.config import get_settings
from app.repositories.erp_repository import ErpContext
from app.schemas.analyze import FeatureVector, SeverityResult
from app.schemas.common import Severity


class SeverityService:
    """Deterministic mock rule boundary until configurable production rules are added."""

    def score(self, features: FeatureVector, erp_context: ErpContext | None) -> SeverityResult:
        reason_codes: list[str] = []
        score = 0.0
        if features.gdacs_alert_level >= 2:
            score += 40.0
            reason_codes.append("GDACS_RED_ALERT")
        if erp_context and erp_context.stock_days < erp_context.safety_stock_days:
            score += 30.0
            reason_codes.append("LOW_STOCK_COVERAGE")
        if features.goldstein_scale <= -5:
            score += 17.3
            reason_codes.append("NEGATIVE_NEWS_TONE")
        severity = Severity.CRITICAL if score >= 70 else Severity.WARNING if score >= 40 else Severity.NORMAL
        return SeverityResult(
            severity=severity,
            score=min(score, 100.0),
            reason_codes=reason_codes,
            rule_version=get_settings().severity_rule_version,
        )
