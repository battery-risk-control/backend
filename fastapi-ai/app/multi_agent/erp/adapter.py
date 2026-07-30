from app.multi_agent.erp.calculator import (
    calculate_erp_metrics,
)
from app.multi_agent.erp.schemas import (
    ErpContext,
    PurchaseOrderStatus,
    SupplierStatus,
)


def build_agent_erp_context(
    context: ErpContext,
) -> dict:
    """
    Spring ERP 원본 구조를 기존 ERP Agent가 사용하는 형태로 변환한다.
    """
    metrics = calculate_erp_metrics(context)
    material = context.material_context

    open_order_statuses = {
        PurchaseOrderStatus.OPEN,
        PurchaseOrderStatus.CONFIRMED,
        PurchaseOrderStatus.PARTIALLY_RECEIVED,
        PurchaseOrderStatus.DELAYED,
    }

    open_orders = sum(
        1
        for order in context.purchase_orders
        if (
            order.order_status in open_order_statuses
            and order.remaining_quantity > 0
        )
    )

    has_alternative_supplier = any(
        supplier.qualified
        and supplier.supplier_status == SupplierStatus.ACTIVE
        for supplier in context.alternative_suppliers
    )

    contract_ids: list[str] = []

    if (
        material is not None
        and material.primary_contract_id is not None
    ):
        contract_ids.append(material.primary_contract_id)

    for order in context.purchase_orders:
        if (
            order.contract_id is not None
            and order.contract_id not in contract_ids
        ):
            contract_ids.append(order.contract_id)

    return {
        "inventory_days": metrics["inventory_days"],
        "safety_stock_days": metrics["safety_stock_days"],
        "open_orders": open_orders,
        "supplier_dependency": (
            material.supplier_dependency_ratio
            if material is not None
            else None
        ),
        "next_inbound_eta_days": metrics["next_eta_days"],
        "has_alternative_supplier": has_alternative_supplier,
        "contract_ids": contract_ids,
        "manual_review_required": (
            metrics["manual_review_required"]
        ),
        "calculation_warnings": metrics["warnings"],
        "expected_supply_gap_days": (
            metrics["expected_supply_gap_days"]
        ),
    }
