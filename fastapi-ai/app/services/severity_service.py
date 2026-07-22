from __future__ import annotations

from app.core.config import get_settings
from app.repositories.erp_repository import ErpContext
from app.schemas.analyze import FeatureVector
from app.schemas.common import Severity
from app.schemas.severity import (
    DataQualityStatus,
    FeocStatus,
    SeverityInput,
    SeverityResult,
)


class SeverityService:
    """Deterministic supply-chain severity rules defined by severity-rule-v1."""

    WARNING_MIN = 30.0
    CRITICAL_MIN = 70.0
    NUMERIC_FIELDS = (
        "inventory_days",
        "safety_stock_days",
        "expected_supply_gap_days",
        "supplier_dependency_ratio",
        "price_change_rate",
        "logistics_delay_days",
        "gdacs_alert_level",
    )

    def evaluate(self, inputs: SeverityInput) -> SeverityResult:
        known_inputs = [name for name in self.NUMERIC_FIELDS if getattr(inputs, name) is not None]
        missing_inputs = [name for name in self.NUMERIC_FIELDS if getattr(inputs, name) is None]
        base_details = {
            "known_inputs": known_inputs,
            "missing_inputs": missing_inputs,
            "forced_critical": False,
            "thresholds": {
                "warning_min": self.WARNING_MIN,
                "critical_min": self.CRITICAL_MIN,
            },
        }

        if inputs.feoc_status == FeocStatus.YES:
            return SeverityResult(
                severity=Severity.CRITICAL,
                score=100.0,
                reason_codes=["FEOC_HARD_GATE"],
                calculation_details={
                    **base_details,
                    "forced_critical": True,
                    "component_scores": {"feoc_hard_gate": 100.0},
                },
                rule_version=get_settings().severity_rule_version,
                mock=True,
            )

        if inputs.data_quality_status == DataQualityStatus.INVALID:
            return self._unknown(
                "INVALID_DATA_QUALITY", base_details, {"data_quality": 0.0}
            )

        if not known_inputs:
            return self._unknown(
                "INSUFFICIENT_DATA", base_details, {"available_signal_count": 0.0}
            )

        component_scores: dict[str, float] = {
            "inventory": self._inventory_score(inputs),
            "supply_gap": self._supply_gap_score(inputs.expected_supply_gap_days),
            "supplier_dependency": self._dependency_score(inputs.supplier_dependency_ratio),
            "price_change": self._price_score(inputs.price_change_rate),
            "logistics_delay": self._delay_score(inputs.logistics_delay_days),
            "gdacs": self._gdacs_score(inputs.gdacs_alert_level),
        }
        reason_codes = self._reason_codes(inputs, component_scores)
        if inputs.data_quality_status == DataQualityStatus.STALE:
            reason_codes.append("STALE_DATA_QUALITY")
        elif inputs.data_quality_status == DataQualityStatus.INCOMPLETE:
            reason_codes.append("INCOMPLETE_DATA_QUALITY")

        score = round(min(sum(component_scores.values()), 100.0), 1)
        severity = (
            Severity.CRITICAL
            if score >= self.CRITICAL_MIN
            else Severity.WARNING
            if score >= self.WARNING_MIN
            else Severity.NORMAL
        )
        if not reason_codes:
            reason_codes.append("NO_RISK_RULE_TRIGGERED")

        inventory_ratio = None
        if (
            inputs.inventory_days is not None
            and inputs.safety_stock_days is not None
            and inputs.safety_stock_days > 0
        ):
            inventory_ratio = round(inputs.inventory_days / inputs.safety_stock_days, 4)

        return SeverityResult(
            severity=severity,
            score=score,
            reason_codes=reason_codes,
            calculation_details={
                **base_details,
                "component_scores": component_scores,
                "inventory_coverage_ratio": inventory_ratio,
            },
            rule_version=get_settings().severity_rule_version,
            mock=True,
        )

    def score(
        self, features: FeatureVector, erp_context: ErpContext | None
    ) -> SeverityResult:
        """Compatibility adapter used by /analyze until Spring owns its orchestration."""
        return self.evaluate(
            SeverityInput(
                inventory_days=(erp_context.stock_days if erp_context else None),
                safety_stock_days=(erp_context.safety_stock_days if erp_context else None),
                expected_supply_gap_days=None,
                supplier_dependency_ratio=None,
                price_change_rate=features.stock_volatility_20d * 100,
                logistics_delay_days=None,
                gdacs_alert_level=features.gdacs_alert_level,
                feoc_status=FeocStatus.UNKNOWN,
                data_quality_status=(
                    DataQualityStatus.VALID if erp_context else DataQualityStatus.UNKNOWN
                ),
            )
        )

    def _unknown(
        self,
        reason_code: str,
        base_details: dict,
        component_scores: dict[str, float],
    ) -> SeverityResult:
        return SeverityResult(
            severity=Severity.UNKNOWN,
            score=0.0,
            reason_codes=[reason_code],
            calculation_details={
                **base_details,
                "component_scores": component_scores,
            },
            rule_version=get_settings().severity_rule_version,
            mock=True,
        )

    @staticmethod
    def _inventory_score(inputs: SeverityInput) -> float:
        if inputs.inventory_days is None or inputs.safety_stock_days is None:
            return 0.0
        if inputs.safety_stock_days <= 0 or inputs.inventory_days >= inputs.safety_stock_days:
            return 0.0
        if inputs.inventory_days < inputs.safety_stock_days * 0.5:
            return 35.0
        return 30.0

    @staticmethod
    def _supply_gap_score(value: float | None) -> float:
        if value is None or value <= 0:
            return 0.0
        if value >= 14:
            return 20.0
        if value >= 7:
            return 12.0
        return 5.0

    @staticmethod
    def _dependency_score(value: float | None) -> float:
        if value is None or value < 0.4:
            return 0.0
        if value >= 0.8:
            return 20.0
        if value >= 0.6:
            return 12.0
        return 5.0

    @staticmethod
    def _price_score(value: float | None) -> float:
        if value is None:
            return 0.0
        magnitude = abs(value)
        if magnitude >= 20:
            return 15.0
        if magnitude >= 10:
            return 10.0
        if magnitude >= 5:
            return 5.0
        return 0.0

    @staticmethod
    def _delay_score(value: float | None) -> float:
        if value is None or value <= 0:
            return 0.0
        if value >= 14:
            return 20.0
        if value >= 7:
            return 12.0
        return 5.0

    @staticmethod
    def _gdacs_score(value: int | None) -> float:
        if value is None or value <= 0:
            return 0.0
        return 40.0 if value >= 2 else 30.0

    @staticmethod
    def _reason_codes(
        inputs: SeverityInput, component_scores: dict[str, float]
    ) -> list[str]:
        reasons: list[str] = []
        if component_scores["inventory"] == 35.0:
            reasons.append("INVENTORY_BELOW_HALF_SAFETY")
        elif component_scores["inventory"] > 0:
            reasons.append("INVENTORY_BELOW_SAFETY")

        if component_scores["supply_gap"] == 20.0:
            reasons.append("LONG_SUPPLY_GAP")
        elif component_scores["supply_gap"] == 12.0:
            reasons.append("SUPPLY_GAP")
        elif component_scores["supply_gap"] > 0:
            reasons.append("SHORT_SUPPLY_GAP")

        if component_scores["supplier_dependency"] == 20.0:
            reasons.append("VERY_HIGH_SUPPLIER_DEPENDENCY")
        elif component_scores["supplier_dependency"] == 12.0:
            reasons.append("HIGH_SUPPLIER_DEPENDENCY")
        elif component_scores["supplier_dependency"] > 0:
            reasons.append("ELEVATED_SUPPLIER_DEPENDENCY")

        if component_scores["price_change"] == 15.0:
            reasons.append("EXTREME_PRICE_CHANGE")
        elif component_scores["price_change"] == 10.0:
            reasons.append("HIGH_PRICE_CHANGE")
        elif component_scores["price_change"] > 0:
            reasons.append("ELEVATED_PRICE_CHANGE")

        if component_scores["logistics_delay"] == 20.0:
            reasons.append("SEVERE_LOGISTICS_DELAY")
        elif component_scores["logistics_delay"] == 12.0:
            reasons.append("LOGISTICS_DELAY")
        elif component_scores["logistics_delay"] > 0:
            reasons.append("SHORT_LOGISTICS_DELAY")

        if inputs.gdacs_alert_level == 2:
            reasons.append("GDACS_RED_ALERT")
        elif inputs.gdacs_alert_level == 1:
            reasons.append("GDACS_ORANGE_ALERT")
        return reasons
