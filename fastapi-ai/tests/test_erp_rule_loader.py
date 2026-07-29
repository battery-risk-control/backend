from app.services.erp_rule_loader import (
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
    rules = loadErpRules()

    assert rules["weights"]["supplyGap"] == 0.35
    assert rules["weights"]["safetyStock"] == 0.20
    assert (
        rules["weights"]["supplierDependency"]
        == 0.20
    )
    assert (
        rules["weights"]["purchaseOrderDelay"]
        == 0.15
    )
    assert (
        rules["weights"]["alternativeSupplier"]
        == 0.10
    )

# 같은 중복 키가 append돼도 즉시 잡힘. 재발 방지용 추가
def testSupplierAssessmentWeightsAreTuned() -> None:
    weights = loadErpRules()["supplierAssessmentRisk"]["weights"]
    assert weights["capacity"] == 0.0
    assert weights["qualification"] == 0.57
    assert weights["supplierStatus"] == 0.43