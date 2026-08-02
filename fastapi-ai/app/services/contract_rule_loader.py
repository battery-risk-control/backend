from functools import lru_cache
from pathlib import Path
from typing import Any

from app.services.erp_rule_loader import (
    RejectDuplicateKeyLoader,
)

import yaml


CONTRACT_RULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "config"
    / "contract_rules.yaml"
)


@lru_cache(maxsize=1)
def loadContractRules() -> dict[str, Any]:
    """
    contract_rules.yaml을 읽어 계약 보호 공백(contract_gap_score) 계산 규칙을 반환한다.

    erp_rule_loader.loadErpRules()와 같은 패턴 — 중복 키를 거부하는 로더를 재사용하고
    lru_cache로 요청마다 파일을 다시 읽지 않는다.
    """

    if not CONTRACT_RULE_PATH.exists():
        raise FileNotFoundError(
            f"계약 규칙 파일을 찾을 수 없습니다: "
            f"{CONTRACT_RULE_PATH}"
        )

    with CONTRACT_RULE_PATH.open(
        "r",
        encoding="utf-8",
    ) as file:
        rules = yaml.load(
            file,
            Loader=RejectDuplicateKeyLoader,
        )

    if not isinstance(rules, dict):
        raise ValueError(
            "계약 규칙 파일의 최상위 구조는 "
            "객체여야 합니다."
        )

    validateContractRules(rules)

    return rules


def validateContractRules(
    rules: dict[str, Any],
) -> None:
    """계약 규칙 파일의 필수 항목을 검사한다."""

    requiredSections = [
        "ruleVersion",
        "protectiveClauseTypes",
        "contractGapRisk",
    ]

    missingSections = [
        section
        for section in requiredSections
        if section not in rules
    ]

    if missingSections:
        raise ValueError(
            "계약 규칙 파일에 필수 항목이 없습니다: "
            + ", ".join(missingSections)
        )

    gapRisk = rules["contractGapRisk"]

    requiredGapRiskKeys = [
        "notSearchable",
        "notFound",
        "protectiveHighSimilarity",
        "protectiveLowSimilarity",
        "nonProtective",
    ]

    missingGapRiskKeys = [
        key
        for key in requiredGapRiskKeys
        if key not in gapRisk
    ]

    if missingGapRiskKeys:
        raise ValueError(
            "contractGapRisk에 필수 항목이 없습니다: "
            + ", ".join(missingGapRiskKeys)
        )
