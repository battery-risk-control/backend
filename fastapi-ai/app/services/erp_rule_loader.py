from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml


ERP_RULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "config"
    / "erp_rules.yaml"
)


@lru_cache(maxsize=1)
def loadErpRules() -> dict[str, Any]:
    """
    erp_rules.yaml을 읽어 ERP 위험 계산 규칙을 반환한다.

    lru_cache를 사용하므로 요청마다 파일을 다시 읽지 않는다.
    """

    if not ERP_RULE_PATH.exists():
        raise FileNotFoundError(
            f"ERP 규칙 파일을 찾을 수 없습니다: "
            f"{ERP_RULE_PATH}"
        )

    with ERP_RULE_PATH.open(
        "r",
        encoding="utf-8",
    ) as file:
        rules = yaml.safe_load(file)

    if not isinstance(rules, dict):
        raise ValueError(
            "ERP 규칙 파일의 최상위 구조는 "
            "객체여야 합니다."
        )

    validateErpRules(rules)

    return rules

def validateErpRules(
    rules: dict[str, Any],
) -> None:
    """ERP 규칙 파일의 필수 항목을 검사한다."""

    requiredSections = [
        "ruleVersion",
        "weights",
        "supplyGapRisk",
        "safetyStockRisk",
        "supplierDependencyRisk",
        "purchaseOrderDelayRisk",
        "alternativeSupplierRisk",
        "exposureLevelThresholds",
        "forcedCriticalRules",
        "forcedWarningRules",
        "supplierAssessmentRisk",
    ]

    missingSections = [
        section
        for section in requiredSections
        if section not in rules
    ]

    if missingSections:
        raise ValueError(
            "ERP 규칙 파일에 필수 항목이 없습니다: "
            + ", ".join(missingSections)
        )

    weights = rules["weights"]

    requiredWeights = [
        "supplyGap",
        "safetyStock",
        "supplierDependency",
        "purchaseOrderDelay",
        "alternativeSupplier",
    ]

    missingWeights = [
        weight
        for weight in requiredWeights
        if weight not in weights
    ]

    if missingWeights:
        raise ValueError(
            "ERP 가중치가 누락되었습니다: "
            + ", ".join(missingWeights)
        )

    weightSum = sum(weights.values())

    if abs(weightSum - 1.0) > 0.000001:
        raise ValueError(
            "ERP 가중치 합계는 1이어야 합니다. "
            f"현재 합계: {weightSum}"
        )

    supplierAssessmentRules = rules[
        "supplierAssessmentRisk"
    ]

    supplierAssessmentWeights = (
        supplierAssessmentRules["weights"]
    )

    requiredSupplierWeights = {
        "qualification",
        "supplierStatus",
        "capacity",
    }

    missingSupplierWeights = (
        requiredSupplierWeights
        - set(supplierAssessmentWeights)
    )

    if missingSupplierWeights:
        raise ValueError(
            "공급사 평가 가중치가 누락되었습니다: "
            + ", ".join(
                sorted(missingSupplierWeights)
            )
        )

    supplierWeightSum = sum(
        supplierAssessmentWeights.values()
    )

    if abs(supplierWeightSum - 1.0) > 0.000001:
        raise ValueError(
            "공급사 평가 가중치 합계는 1이어야 합니다. "
            f"현재 합계: {supplierWeightSum}"
        )