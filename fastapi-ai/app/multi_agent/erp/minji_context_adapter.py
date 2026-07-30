from datetime import date

from app.repositories.erp_repository import (
    ErpContext as MinjiErpContext,
)


def build_multi_agent_erp_state(
    context: MinjiErpContext,
    as_of: date,
) -> dict:
    expected_inbound_date = date.fromisoformat(
        context.expected_inbound_date
    )
    next_eta_days = max(
        (expected_inbound_date - as_of).days,
        0,
    )

    rag_supplier_id = (
        context.supplier_ids[0]
        if context.supplier_ids
        else None
    )
    rag_contract_id = (
        context.contract_ids[0]
        if context.contract_ids
        else None
    )

    return {
        "erp_context": {
            "inventory_days": context.stock_days,
            "safety_stock_days": (
                context.safety_stock_days
            ),
            "next_inbound_eta_days": next_eta_days,
            "contract_ids": context.contract_ids,

            # 현재 Minji ERP Context에 없는 값은
            # 임의로 추정하지 않는다.
            "supplier_dependency": None,
            "has_alternative_supplier": None,
            "manual_review_required": True,
            "calculation_warnings": [
                "현재 ERP Context에는 미입고 발주 수량, "
                "공급사 의존도 및 대체 공급사 정보가 없어 "
                "추가 확인이 필요합니다."
            ],
            "expected_supply_gap_days": max(
                next_eta_days - context.stock_days,
                0,
            ),
        },
        "rag_material_id": context.material_id,
        "rag_supplier_id": rag_supplier_id,
        "rag_contract_id": rag_contract_id,
    }