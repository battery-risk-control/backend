from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml


ERP_RULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "config"
    / "erp_rules.yaml"
)


class DuplicateKeyError(ValueError):
    """YAML에 같은 키가 두 번 정의됐을 때 발생한다."""


class RejectDuplicateKeyLoader(yaml.SafeLoader):
    """중복 키를 조용히 덮어쓰지 않고 즉시 예외로 알리는 SafeLoader.

    YAML 표준은 같은 키가 두 번 나오면 뒤엣것이 앞을 덮어쓰며, PyYAML은 경고조차 내지 않는다.
    실제로 브랜치 병합 과정에서 supplierAssessmentRisk 블록이 파일 끝에 한 번 더 append돼
    의도적으로 0으로 죽여둔 capacity 가중치가 0.30으로 되살아난 적이 있다. 가중치 합이 1.0이라
    validateErpRules()도 통과했고, 테스트도 통과했다 — 아무도 모르게 점수만 부풀려졌다.
    """


def constructMappingRejectingDuplicates(
    loader: "RejectDuplicateKeyLoader",
    node: yaml.MappingNode,
    deep: bool = False,
) -> dict[Any, Any]:
    """매핑을 만들면서 키 중복을 검사한다. 중복이면 행 번호와 함께 예외를 던진다."""

    mapping: dict[Any, Any] = {}
    firstSeenLine: dict[Any, int] = {}

    for keyNode, valueNode in node.value:
        key = loader.construct_object(keyNode, deep=deep)
        line = keyNode.start_mark.line + 1

        if key in mapping:
            raise DuplicateKeyError(
                f"YAML에 같은 키가 두 번 정의되어 있습니다: "
                f"'{key}' ({firstSeenLine[key]}행, {line}행). "
                "뒤에 나온 값이 앞을 덮어써 앞의 설정이 조용히 무효가 되므로, "
                "한쪽을 지우고 하나만 남기십시오."
            )

        firstSeenLine[key] = line
        mapping[key] = loader.construct_object(
            valueNode,
            deep=deep,
        )

    return mapping


RejectDuplicateKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    constructMappingRejectingDuplicates,
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
        # safe_load 대신 중복 키를 거부하는 로더를 쓴다 — RejectDuplicateKeyLoader 주석 참고.
        rules = yaml.load(
            file,
            Loader=RejectDuplicateKeyLoader,
        )

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
        "contractProtection",
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