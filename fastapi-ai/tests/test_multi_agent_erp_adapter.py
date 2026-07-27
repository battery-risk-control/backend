from decimal import Decimal

from app.multi_agent.erp.adapter import (
    build_agent_erp_context,
)
from app.multi_agent.erp.schemas import ErpContext


def test_builds_legacy_context_for_erp_agent():
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
                "alternativeSupplierStatus": "APPROVED",
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
            "alternativeSuppliers": [
                {
                    "supplierId": "SUP-COD-02",
                    "materialId": "MAT-CO-SULF",
                    "contractId": "CTR-011",
                    "supplierStatus": "ACTIVE",
                    "availableCapacityQuantity": 3000,
                    "leadTimeDays": 14,
                    "qualified": True,
                }
            ],
        }
    )

    result = build_agent_erp_context(context)

    assert result["inventory_days"] == Decimal("6.5")
    assert result["safety_stock_days"] == Decimal("18")
    assert result["open_orders"] == 1
    assert result["supplier_dependency"] == Decimal("0.84")
    assert result["next_inbound_eta_days"] == 8
    assert result["has_alternative_supplier"] is True
    assert result["contract_ids"] == ["CTR-010"]
    assert result["manual_review_required"] is False
