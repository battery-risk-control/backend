from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpRiskComponents,
    ExposureLevel,
    PurchaseOrderStatus,
)

from app.services.erp_calculator import (
    calculateAlternativeSupplierRiskScore,
    calculateDependencyRiskScore,
    calculateErpExposureScore,
    calculatePurchaseOrderDelayRiskScore,
    calculateSafetyStockRiskScore,
    calculateSupplyGapRiskScore,
    determineExposureLevel,
)

from app.services.erp_rule_loader import (
    loadErpRules,
)


def testCobaltRiskScores() -> None:
    rules = loadErpRules()

    assert (
        calculateSupplyGapRiskScore(
            expectedSupplyGapDays=8.5,
            rules=rules,
        )
        == 100
    )

    assert (
        calculateSafetyStockRiskScore(
            inventoryDays=6.5,
            safetyStockDays=18,
            rules=rules,
        )
        == 100
    )

    assert (
        calculateDependencyRiskScore(
            supplierDependencyRatio=0.84,
            rules=rules,
        )
        == 100
    )

    assert (
        calculatePurchaseOrderDelayRiskScore(
            purchaseOrderStatus=(
                PurchaseOrderStatus.DELAYED
            ),
            rules=rules,
        )
        == 100
    )

    assert (
        calculateAlternativeSupplierRiskScore(
            alternativeSupplierStatus=(
                AlternativeSupplierStatus.NONE
            ),
            rules=rules,
        )
        == 100
    )


def testCobaltExposureScore() -> None:
    rules = loadErpRules()

    riskComponents = ErpRiskComponents(
        gapRiskScore=100,
        safetyStockRiskScore=100,
        dependencyRiskScore=100,
        purchaseOrderDelayRiskScore=100,
        alternativeSupplierRiskScore=100,
    )

    score = calculateErpExposureScore(
        riskComponents=riskComponents,
        rules=rules,
    )

    assert score == 100


def testNickelExposureScore() -> None:
    rules = loadErpRules()

    riskComponents = ErpRiskComponents(
        gapRiskScore=0,
        safetyStockRiskScore=80,
        dependencyRiskScore=70,
        purchaseOrderDelayRiskScore=0,
        alternativeSupplierRiskScore=0,
    )

    score = calculateErpExposureScore(
        riskComponents=riskComponents,
        rules=rules,
    )

    # 0*0.35 + 80*0.20 + 70*0.20
    # = 16 + 14
    # = 30
    assert score == 30


def testNormalExposureLevel() -> None:
    rules = loadErpRules()

    result = determineExposureLevel(
        erpExposureScore=14,
        forcedCritical=False,
        forcedWarning=False,
        rules=rules,
    )

    assert result is ExposureLevel.NORMAL


def testWarningExposureLevel() -> None:
    rules = loadErpRules()

    result = determineExposureLevel(
        erpExposureScore=30,
        forcedCritical=False,
        forcedWarning=False,
        rules=rules,
    )

    assert result is ExposureLevel.WARNING


def testForcedCriticalExposureLevel() -> None:
    rules = loadErpRules()

    result = determineExposureLevel(
        erpExposureScore=45,
        forcedCritical=True,
        forcedWarning=True,
        rules=rules,
    )

    assert result is ExposureLevel.CRITICAL


def testUnknownExposureLevel() -> None:
    rules = loadErpRules()

    result = determineExposureLevel(
        erpExposureScore=None,
        forcedCritical=False,
        forcedWarning=False,
        rules=rules,
    )

    assert result is ExposureLevel.UNKNOWN