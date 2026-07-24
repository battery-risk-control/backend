from decimal import Decimal
from typing import TypedDict

from app.erp.schemas import ErpContext


class ErpCalculationResult(TypedDict):
    available_quantity: Decimal
    inventory_days: Decimal | None
    safety_stock_days: Decimal | None
    next_eta_days: int | None
    expected_supply_gap_days: Decimal | None
    stockout_before_eta: bool
    manual_review_required: bool
    warnings: list[str]

def calculate_available_quantity(context: ErpContext) -> Decimal:
    material = context.material_context

    if material is None:
        return Decimal("0")

    deductions = (
        material.reserved_quantity
        + material.blocked_quantity
        + material.quality_hold_quantity
    )
    available_quantity = material.on_hand_quantity - deductions

    return max(available_quantity, Decimal("0"))

def calculate_inventory_days(
    available_quantity: Decimal,
    average_daily_usage: Decimal,
) -> Decimal | None:
    if average_daily_usage == 0:
        return None

    return available_quantity / average_daily_usage

def calculate_safety_stock_days(
    safety_stock_quantity: Decimal,
    average_daily_usage: Decimal,
) -> Decimal | None:
    if average_daily_usage == 0:
        return None

    return safety_stock_quantity / average_daily_usage

def calculate_next_eta_days(context: ErpContext) -> int | None:
    material = context.material_context

    if material is None:
        return None

    eligible_arrival_dates = [
        order.effective_arrival_date
        for order in context.purchase_orders
        if order.eligible_for_eta
        and order.effective_arrival_date is not None
    ]

    if not eligible_arrival_dates:
        return None

    next_arrival_date = min(eligible_arrival_dates)
    snapshot_date = material.inventory_snapshot_at.date()
    remaining_days = (next_arrival_date - snapshot_date).days

    return max(remaining_days, 0)

def calculate_supply_gap_days(
    inventory_days: Decimal | None,
    next_eta_days: int | None,
) -> Decimal | None:
    if inventory_days is None or next_eta_days is None:
        return None

    supply_gap_days = Decimal(next_eta_days) - inventory_days

    return max(supply_gap_days, Decimal("0"))

def calculate_erp_metrics(
    context: ErpContext,
) -> ErpCalculationResult:
    material = context.material_context
    warnings: list[str] = []
    manual_review_required = False

    if material is None:
        return {
            "available_quantity": Decimal("0"),
            "inventory_days": None,
            "safety_stock_days": None,
            "next_eta_days": None,
            "expected_supply_gap_days": None,
            "stockout_before_eta": False,
            "manual_review_required": True,
            "warnings": [
                "원자재 재고 데이터가 없어 ERP 계산을 수행할 수 없습니다."
            ],
        }

    available_quantity = calculate_available_quantity(context)

    total_deductions = (
        material.reserved_quantity
        + material.blocked_quantity
        + material.quality_hold_quantity
    )

    if total_deductions > material.on_hand_quantity:
        manual_review_required = True
        warnings.append(
            "차감 수량이 현재 보유 수량보다 커 담당자 확인이 필요합니다."
        )

    inventory_days = calculate_inventory_days(
        available_quantity,
        material.average_daily_usage,
    )
    safety_stock_days = calculate_safety_stock_days(
        material.safety_stock_quantity,
        material.average_daily_usage,
    )

    if material.average_daily_usage == 0:
        manual_review_required = True
        warnings.append(
            "일평균 사용량이 0이어서 재고 일수를 계산할 수 없습니다."
        )

    next_eta_days = calculate_next_eta_days(context)

    if next_eta_days is None:
        manual_review_required = True
        warnings.append(
            "유효한 입고 예정일이 없어 공급 공백을 계산할 수 없습니다."
        )

    expected_supply_gap_days = calculate_supply_gap_days(
        inventory_days,
        next_eta_days,
    )
    stockout_before_eta = (
        expected_supply_gap_days is not None
        and expected_supply_gap_days > 0
    )

    for supplier in context.alternative_suppliers:
        if (
            supplier.qualified
            and supplier.available_capacity_quantity is None
        ):
            manual_review_required = True
            warnings.append(
                "대체 공급사의 공급 가능 수량이 없어 담당자 확인이 필요합니다."
            )
            break

    return {
        "available_quantity": available_quantity,
        "inventory_days": inventory_days,
        "safety_stock_days": safety_stock_days,
        "next_eta_days": next_eta_days,
        "expected_supply_gap_days": expected_supply_gap_days,
        "stockout_before_eta": stockout_before_eta,
        "manual_review_required": manual_review_required,
        "warnings": warnings,
    }
