import io

import yaml

from app.services.erp_rule_loader import (
    DuplicateKeyError,
    RejectDuplicateKeyLoader,
    loadErpRules,
)


def testLoadErpRules() -> None:
    rules = loadErpRules()

    assert (
        rules["ruleVersion"]
        == "erp-exposure-v0.1"
    )


def testWeightSum() -> None:
    rules = loadErpRules()

    weightSum = sum(
        rules["weights"].values()
    )

    assert abs(weightSum - 1.0) < 0.000001


def testRequiredWeights() -> None:
    # 원래 5개 가중치는 0.35/0.20/0.20/0.15/0.10이었으나, contractProtection(0.15)이
    # 새로 추가되면서 나머지 5개를 원래 비율 그대로 0.85로 비례 축소했다
    # (calculateErpExposureScore가 contractProtectionRiskScore가 없을 때 /0.85로
    # 재정규화하므로, 최초 계산 결과는 예전과 완전히 동일해야 한다 — 그래서 반올림하지
    # 않은 정확한 값을 쓴다: 0.35*0.85=0.2975, 0.15*0.85=0.1275, 0.10*0.85=0.085).
    rules = loadErpRules()

    assert rules["weights"]["supplyGap"] == 0.2975
    assert rules["weights"]["safetyStock"] == 0.17
    assert (
        rules["weights"]["supplierDependency"]
        == 0.17
    )
    assert (
        rules["weights"]["purchaseOrderDelay"]
        == 0.1275
    )
    assert (
        rules["weights"]["alternativeSupplier"]
        == 0.085
    )
    assert (
        rules["weights"]["contractProtection"]
        == 0.15
    )

# 같은 중복 키가 append돼도 즉시 잡힘. 재발 방지용 추가
def testSupplierAssessmentWeightsAreTuned() -> None:
    weights = loadErpRules()["supplierAssessmentRisk"]["weights"]
    assert weights["capacity"] == 0.0
    assert weights["qualification"] == 0.57
    assert weights["supplierStatus"] == 0.43


# 아래 두 건은 위 단언이 "왜 필요한지"를 지키는 장치다.
# 브랜치 병합 때 supplierAssessmentRisk 블록이 파일 끝에 한 번 더 append되어
# capacity 가중치가 0.0 -> 0.30으로 조용히 되살아난 적이 있다(가중치 합이 1.0이라
# validateErpRules()도 통과했다). safe_load는 중복 키를 경고 없이 덮어쓰므로
# 로더 자체가 거부하지 않으면 아무도 알아채지 못한다.
def testDuplicateTopLevelKeyIsRejected() -> None:
    document = io.StringIO(
        "supplierAssessmentRisk:\n"
        "  weights:\n"
        "    capacity: 0.0\n"
        "\n"
        "supplierAssessmentRisk:\n"
        "  weights:\n"
        "    capacity: 0.30\n"
    )

    try:
        yaml.load(
            document,
            Loader=RejectDuplicateKeyLoader,
        )
    except DuplicateKeyError as error:
        assert "supplierAssessmentRisk" in str(error)
        # 어느 행이 겹쳤는지 알려줘야 고칠 수 있다.
        assert "1행" in str(error)
        assert "5행" in str(error)
    else:
        raise AssertionError(
            "중복 최상위 키가 예외 없이 통과했습니다."
        )


def testDuplicateNestedKeyIsRejected() -> None:
    document = io.StringIO(
        "supplierAssessmentRisk:\n"
        "  weights:\n"
        "    capacity: 0.0\n"
        "    capacity: 0.30\n"
    )

    try:
        yaml.load(
            document,
            Loader=RejectDuplicateKeyLoader,
        )
    except DuplicateKeyError as error:
        assert "capacity" in str(error)
    else:
        raise AssertionError(
            "중첩 중복 키가 예외 없이 통과했습니다."
        )