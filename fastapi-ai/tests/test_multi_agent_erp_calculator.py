from decimal import Decimal

from app.multi_agent.erp.calculator import (
    calculate_available_quantity,
    calculate_erp_metrics,
    calculate_inventory_days,
    calculate_next_eta_days,
    calculate_safety_stock_days,
    calculate_supply_gap_days,

)
from app.multi_agent.erp.schemas import ErpContext


def test_calculates_available_quantity():
    context = ErpContext.model_validate(
        {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": "2026-07-22T08:00:00+09:00",
            }
        }
    )

    result = calculate_available_quantity(context)

    assert result == Decimal("6500")

def test_calculates_inventory_days():
    result = calculate_inventory_days(
        available_quantity=Decimal("6500"),
        average_daily_usage=Decimal("1000"),
    )

    assert result == Decimal("6.5")

def test_inventory_days_is_none_when_daily_usage_is_zero():
    result = calculate_inventory_days(
        available_quantity=Decimal("6500"),
        average_daily_usage=Decimal("0"),
    )

    assert result is None

def test_calculates_safety_stock_days():
    result = calculate_safety_stock_days(
        safety_stock_quantity=Decimal("18000"),
        average_daily_usage=Decimal("1000"),
    )

    assert result == Decimal("18")


def test_calculates_next_eta_days_from_earliest_order():
    context = ErpContext.model_validate(
        {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": "2026-07-22T08:00:00+09:00",
            },
            "purchaseOrders": [
                {
                    "purchaseOrderItemId": "POI-001",
                    "purchaseOrderId": "PO-001",
                    "materialId": "MAT-CO-SULF",
                    "supplierId": "SUP-COD-01",
                    "contractId": "CTR-010",
                    "remainingQuantity": 5000,
                    "orderStatus": "DELAYED",
                    "effectiveArrivalDate": "2026-07-30",
                    "eligibleForEta": True,
                },
                {
                    "purchaseOrderItemId": "POI-002",
                    "purchaseOrderId": "PO-002",
                    "materialId": "MAT-CO-SULF",
                    "supplierId": "SUP-COD-01",
                    "contractId": "CTR-010",
                    "remainingQuantity": 3000,
                    "orderStatus": "CONFIRMED",
                    "effectiveArrivalDate": "2026-07-27",
                    "eligibleForEta": True,
                },
            ],
        }
    )

    result = calculate_next_eta_days(context)

    assert result == 5

def test_next_eta_days_is_none_without_valid_order():
    context = ErpContext.model_validate(
        {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": "2026-07-22T08:00:00+09:00",
            },
            "purchaseOrders": [],
        }
    )

    result = calculate_next_eta_days(context)

    assert result is None

def test_calculates_supply_gap_days():
    result = calculate_supply_gap_days(
        inventory_days=Decimal("6.5"),
        next_eta_days=10,
    )

    assert result == Decimal("3.5")

def test_supply_gap_is_zero_when_stock_lasts_until_arrival():
    result = calculate_supply_gap_days(
        inventory_days=Decimal("6.5"),
        next_eta_days=5,
    )

    assert result == Decimal("0")

def test_calculates_complete_erp_metrics():
    context = ErpContext.model_validate(
        {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": "2026-07-22T08:00:00+09:00",
            },
            "purchaseOrders": [
                {
                    "purchaseOrderItemId": "POI-001",
                    "purchaseOrderId": "PO-001",
                    "materialId": "MAT-CO-SULF",
                    "supplierId": "SUP-COD-01",
                    "contractId": "CTR-010",
                    "remainingQuantity": 5000,
                    "orderStatus": "DELAYED",
                    "effectiveArrivalDate": "2026-07-30",
                    "eligibleForEta": True,
                }
            ],
        }
    )

    result = calculate_erp_metrics(context)

    assert result["available_quantity"] == Decimal("6500")
    assert result["inventory_days"] == Decimal("6.5")
    assert result["safety_stock_days"] == Decimal("18")
    assert result["next_eta_days"] == 8
    assert result["expected_supply_gap_days"] == Decimal("1.5")
    assert result["stockout_before_eta"] is True
    assert result["manual_review_required"] is False
    assert result["warnings"] == []

def test_erp_metrics_require_review_when_daily_usage_is_zero():
    context = ErpContext.model_validate(
        {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 0,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": "2026-07-22T08:00:00+09:00",
            }
        }
    )

    result = calculate_erp_metrics(context)

    assert result["inventory_days"] is None
    assert result["safety_stock_days"] is None
    assert result["expected_supply_gap_days"] is None
    assert result["stockout_before_eta"] is False
    assert result["manual_review_required"] is True
    assert (
        "일평균 사용량이 0이어서 재고 일수를 계산할 수 없습니다."
        in result["warnings"]
    )
