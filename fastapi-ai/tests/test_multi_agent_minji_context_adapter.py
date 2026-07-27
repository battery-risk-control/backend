from datetime import date

from app.multi_agent.erp.minji_context_adapter import (
    build_multi_agent_erp_state,
)
from app.repositories.erp_repository import ErpContext


def test_builds_agent_and_rag_context_from_minji_erp():
    context = ErpContext(
        material_id=1,
        supplier_ids=[11],
        contract_ids=[501],
        stock_days=12,
        safety_stock_days=20,
        expected_inbound_date="2026-08-04",
    )

    result = build_multi_agent_erp_state(
        context=context,
        as_of=date(2026, 7, 22),
    )

    erp_context = result["erp_context"]

    assert erp_context["inventory_days"] == 12
    assert erp_context["safety_stock_days"] == 20
    assert erp_context["next_inbound_eta_days"] == 13
    assert erp_context["expected_supply_gap_days"] == 1
    assert erp_context["contract_ids"] == [501]
    assert erp_context["manual_review_required"] is True

    assert result["rag_material_id"] == 1
    assert result["rag_supplier_id"] == 11
    assert result["rag_contract_id"] == 501