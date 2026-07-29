from __future__ import annotations

from app.core.config import get_settings
from app.models.severity_engine import score_severity
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
        """severity-rule-v1: ERP 노출도 기반 심각도 계산.

        [보류 중인 정리 계획 — 2026-07-29]
        ERP 노출도 계산은 soojung의 erp_calculator(erp-exposure-v0.1)로 단일화하기로 합의됐다.
        (이 메서드는 2026-07-22 S11 구현 시 만들어졌고, 4일 뒤 멀티에이전트 설계안 기준의
        ERP Exposure Agent가 별도로 들어오면서 supplyGap·safetyStock·dependency·PO delay가
        중복됐다. 기능 결함은 아니며 두 경로가 각각 정상 동작 중이다.)

        다만 아래 경로가 이 메서드에 의존하고 있어 변환 어댑터 없이는 제거할 수 없다:
            POST /api/v1/briefings (F5)
              → BriefingService.callSeverity() → /api/v1/internal/severity/score → 여기
              → severity_assessments 저장
                  → briefings.assessment_id (F7 근거 계보)
                  → DashboardRepository (F11 집계)

        제거 전 필요한 것:
          1) FEOC 하드게이트(F8)를 erp_rules.yaml의 forcedCriticalRules로 이관
             — 현재 FEOC 강제격상은 이 메서드에만 있다(기준선 S5 참고)
          2) ErpExposureResponse → SeverityResult 변환 어댑터
             (exposureLevel→severity, erpExposureScore→score, riskComponents→reason_codes)
          3) 교체 전후 등급 변화 비교 — 임계값이 다르다(v1: WARNING 30/CRITICAL 70)

        회귀 기준선(8종 시나리오): docs/severity-v1-baseline.md
        신규 기능은 이 메서드가 아니라 POST /api/v1/internal/erp/exposure를 사용할 것.
        """
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

    def score(self, features: FeatureVector) -> SeverityResult:
        """[surin F3] /analyze 이벤트 심각도: severity_engine(severity-rule-v0.2-realtime, 4,081건 실측 검증).

        merge의 evaluate()(severity-rule-v1, ERP 노출도 기반)는 internal.py /severity/score와
        F9/ERP 경로용으로 그대로 보존한다(둘 다 공존).
        """
        return score_severity(features)

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
