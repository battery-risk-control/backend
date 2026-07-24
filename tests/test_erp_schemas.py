from decimal import Decimal
import pytest
from pydantic import ValidationError

from app.erp.schemas import (
    MaterialContext,
    PurchaseOrderItem,
    AlternativeSupplierItem,
    ErpContext,
)


def create_valid_material_context() -> dict:
    return {
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

        "inventorySnapshotAt": (
            "2026-07-22T08:00:00+09:00"
        ),
    }


def test_material_context_accepts_camel_case_json():
    data = create_valid_material_context()

    context = MaterialContext.model_validate(data)

    assert context.material_id == "MAT-CO-SULF"
    assert context.on_hand_quantity == 7280
    assert (
        context.supplier_dependency_ratio
        == Decimal("0.84")
    )
    assert context.primary_contract_id == "CTR-010"

    # 외부 JSON으로 반환할 때는 다시 camelCase 사용
    dumped = context.model_dump(
        by_alias=True,
        mode="json",
    )

    assert dumped["materialId"] == "MAT-CO-SULF"
    assert dumped["onHandQuantity"] == "7280"


def test_material_context_rejects_negative_quantity():
    data = create_valid_material_context()
    data["onHandQuantity"] = -1

    with pytest.raises(ValidationError):
        MaterialContext.model_validate(data)


def test_material_context_rejects_invalid_dependency_ratio():
    data = create_valid_material_context()

    # 84%는 84가 아니라 0.84로 전달해야 한다.
    data["supplierDependencyRatio"] = 84

    with pytest.raises(ValidationError):
        MaterialContext.model_validate(data)


def test_material_context_rejects_unknown_field():
    data = create_valid_material_context()
    data["unexpectedField"] = "not-allowed"

    with pytest.raises(ValidationError):
        MaterialContext.model_validate(data)

def test_purchase_order_item_accepts_camel_case_json():
    data = {
        "purchaseOrderItemId": "POI-001",
        "purchaseOrderId": "PO-001",
        "materialId": "MAT-CO-SULF",
        "supplierId": "SUP-COD-01",
        "contractId": "CTR-010",
        "remainingQuantity": "5000",
        "orderStatus": "DELAYED",
        "effectiveArrivalDate": "2026-07-30",
        "eligibleForEta": True,
    }

    item = PurchaseOrderItem.model_validate(data)

    assert item.purchase_order_item_id == "POI-001"
    assert item.contract_id == "CTR-010"
    assert item.remaining_quantity == Decimal("5000")
    assert item.effective_arrival_date.isoformat() == "2026-07-30"
    assert item.eligible_for_eta is True


def test_purchase_order_item_rejects_negative_quantity():
    data = {
        "purchaseOrderItemId": "POI-001",
        "purchaseOrderId": "PO-001",
        "materialId": "MAT-CO-SULF",
        "supplierId": "SUP-COD-01",
        "contractId": "CTR-010",
        "remainingQuantity": "-1",
        "orderStatus": "DELAYED",
        "effectiveArrivalDate": "2026-07-30",
        "eligibleForEta": True,
    }

    with pytest.raises(ValidationError):
        PurchaseOrderItem.model_validate(data)

def test_alternative_supplier_accepts_camel_case_json():
    data = {
        "supplierId": "SUP-COD-02",
        "materialId": "MAT-CO-SULF",
        "contractId": "CTR-011",
        "supplierStatus": "ACTIVE",
        "availableCapacityQuantity": "3000",
        "leadTimeDays": 14,
        "qualified": True,
    }

    supplier = AlternativeSupplierItem.model_validate(data)

    assert supplier.supplier_id == "SUP-COD-02"
    assert supplier.contract_id == "CTR-011"
    assert supplier.available_capacity_quantity == Decimal("3000")
    assert supplier.lead_time_days == 14
    assert supplier.qualified is True

def test_alternative_supplier_accepts_missing_capacity():
    data = {
        "supplierId": "SUP-COD-02",
        "materialId": "MAT-CO-SULF",
        "contractId": None,
        "supplierStatus": "ACTIVE",
        "availableCapacityQuantity": None,
        "leadTimeDays": None,
        "qualified": True,
    }

    supplier = AlternativeSupplierItem.model_validate(data)

    assert supplier.available_capacity_quantity is None
    assert supplier.lead_time_days is None

def test_erp_context_accepts_nested_camel_case_json():
    data = {
        "materialContext": create_valid_material_context(),
        "purchaseOrders": [
            {
                "purchaseOrderItemId": "POI-001",
                "purchaseOrderId": "PO-001",
                "materialId": "MAT-CO-SULF",
                "supplierId": "SUP-COD-01",
                "contractId": "CTR-010",
                "remainingQuantity": "5000",
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
                "availableCapacityQuantity": "3000",
                "leadTimeDays": 14,
                "qualified": True,
            }
        ],
    }

    context = ErpContext.model_validate(data)

    assert context.material_context is not None
    assert context.material_context.material_id == "MAT-CO-SULF"
    assert len(context.purchase_orders) == 1
    assert context.purchase_orders[0].purchase_order_id == "PO-001"
    assert len(context.alternative_suppliers) == 1
    assert context.alternative_suppliers[0].supplier_id == "SUP-COD-02"
