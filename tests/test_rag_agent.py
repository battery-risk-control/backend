from app.agents.rag_agent import search_contracts_node


def test_rag_node_returns_logistics_clause():
    state = {
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"]
    }

    result = search_contracts_node(state)
    contract_findings = result["contract_findings"]

    assert len(contract_findings) == 1
    assert contract_findings[0]["material"] == "Nickel"
    assert contract_findings[0]["clause_type"] == "delivery_delay"
    assert contract_findings[0]["source_type"] == "mock"


def test_rag_node_returns_empty_list_for_unknown_domain():
    state = {
        "impact_domain_final": "unknown",
        "affected_materials": []
    }

    result = search_contracts_node(state)

    assert result["contract_findings"] == []


def test_rag_node_uses_erp_agent_context():
    state = {
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"],
        "affected_contract_ids": [
            "CONTRACT-NICKEL-001"
        ],
        "questions_for_contract_agent": [
            "납기 지연 시 적용할 수 있는 조항이 있는가?"
        ],
    }

    result = search_contracts_node(state)

    assessment = result["contract_assessment"]
    finding = result["contract_findings"][0]

    assert assessment["contract_gap_score"] == 30
    assert assessment["protection_status"] == "partial"

    assert assessment["questions_received"] == [
        "납기 지연 시 적용할 수 있는 조항이 있는가?"
    ]

    assert finding["contract_id"] == "CONTRACT-NICKEL-001"
    assert finding["clause_type"] == "delivery_delay"

    assert len(result["questions_for_erp_agent"]) == 1
