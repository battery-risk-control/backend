from unittest.mock import patch

from app.multi_agent.api_schemas import (
    MultiAgentBriefingResponse,
)
from app.multi_agent.kg.node import (
    analyze_kg_context_node,
    extract_kg_fields,
)


SHORTAGE_RESPONSE = {
    "matched": True,
    "country": "CD",
    "affected_materials": ["cobalt"],
    "results": [
        {
            "country": "CD",
            "material_category": "COBALT",
            "affected_suppliers": ["SUP-COD-01"],
            "inventory_checks": {
                "MAT-CO-SULF": {"status": "SHORTAGE"},
            },
            "affected_inbound_contracts": ["CTR-010"],
            "affected_products": ["PRD-001"],
            "affected_outbound_contracts": ["CTR-OUT-001"],
            "impact_score": 1234.0,
            "alternative_suppliers": [
                {"supplier_id": "SUP-AUS-01"},
            ],
            "evidence_path": (
                "콩고민주공화국 코발트 공급사 재고 부족 확인"
            ),
        },
    ],
}

NO_SHORTAGE_RESPONSE = {
    "matched": True,
    "country": "CL",
    "affected_materials": ["lithium"],
    "results": [
        {
            "country": "CL",
            "material_category": "LITHIUM",
            "affected_suppliers": ["SUP-CHL-01"],
            "inventory_checks": {
                "MAT-LI-CARB": {"status": "SUFFICIENT"},
            },
            "affected_inbound_contracts": [],
            "affected_products": [],
            "affected_outbound_contracts": [],
            "impact_score": 0.0,
            "alternative_suppliers": [],
            "evidence_path": "칠레 리튬 재고 충분",
        },
    ],
}

NO_MATCH_RESPONSE = {
    "matched": False,
    "country": "US",
    "affected_materials": ["unknown-material"],
    "results": [],
}


def test_extract_kg_fields_detects_shortage():
    fields = extract_kg_fields(SHORTAGE_RESPONSE)

    assert fields["kg_matched"] is True
    assert fields["kg_shortage_detected"] is True
    assert fields["kg_affected_suppliers"] == [
        "SUP-COD-01",
    ]
    assert fields["kg_affected_contract_ids"] == [
        "CTR-010",
    ]
    assert fields["kg_affected_outbound_contract_ids"] == [
        "CTR-OUT-001",
    ]
    assert fields["kg_impact_score"] == 1234.0
    assert fields["kg_evidence_paths"] == [
        "콩고민주공화국 코발트 공급사 재고 부족 확인",
    ]


def test_extract_kg_fields_no_shortage():
    fields = extract_kg_fields(NO_SHORTAGE_RESPONSE)

    assert fields["kg_matched"] is True
    assert fields["kg_shortage_detected"] is False
    assert fields["kg_affected_contract_ids"] == []


def test_analyze_kg_context_node_passes_gate_on_shortage():
    with patch(
        "app.multi_agent.kg.node.resolve_kg_context",
        return_value=SHORTAGE_RESPONSE,
    ):
        result = analyze_kg_context_node(
            {
                "country": "CD",
                "affected_materials": ["cobalt"],
            },
        )

    assert result["kg_shortage_detected"] is True
    assert "briefing" not in result
    assert result["kg_context"] == SHORTAGE_RESPONSE


def test_analyze_kg_context_node_short_circuits_on_no_shortage():
    with patch(
        "app.multi_agent.kg.node.resolve_kg_context",
        return_value=NO_SHORTAGE_RESPONSE,
    ):
        result = analyze_kg_context_node(
            {
                "country": "CL",
                "affected_materials": ["lithium"],
            },
        )

    assert result["kg_shortage_detected"] is False
    assert result["procurement_risk_level"] == "normal"
    assert result["briefing"]
    assert result["review_passed"] is True
    assert_satisfies_response_schema(result)


def test_analyze_kg_context_node_short_circuits_on_no_match():
    with patch(
        "app.multi_agent.kg.node.resolve_kg_context",
        return_value=NO_MATCH_RESPONSE,
    ):
        result = analyze_kg_context_node(
            {
                "country": "US",
                "affected_materials": [
                    "unknown-material",
                ],
            },
        )

    assert result["kg_matched"] is False
    assert result["kg_shortage_detected"] is False
    assert result["briefing"]
    assert result["review_passed"] is True
    assert_satisfies_response_schema(result)


def assert_satisfies_response_schema(node_result: dict) -> None:
    """조기 종료 경로가 API 응답 스키마의 필수 필드를 다 채우는지 확인한다.

    Docker 실증에서 이걸 안 채워 500(ValidationError)이 났던 회귀를 막는다.
    """

    state = {
        "news_id": "news-001",
        "impact_domain_final": "unknown",
        **node_result,
    }
    response_fields = {
        field_name: state[field_name]
        for field_name in MultiAgentBriefingResponse.model_fields
        if field_name in state
    }
    MultiAgentBriefingResponse.model_validate(response_fields)


def test_analyze_kg_context_node_skips_call_without_country():
    with patch(
        "app.multi_agent.kg.node.resolve_kg_context",
    ) as mock_resolve:
        result = analyze_kg_context_node(
            {
                "affected_materials": ["cobalt"],
            },
        )

    mock_resolve.assert_not_called()
    assert result["kg_matched"] is False
    assert result["kg_shortage_detected"] is False


def _bypass_settings():
    """KG_GATE_ENABLED=false 상태의 Settings. get_settings가 lru_cache라 값을 갈아끼우지 않고
    호출 자체를 patch한다 — 캐시를 비우면 같은 프로세스의 다른 테스트에 영향이 간다."""
    from dataclasses import replace

    from app.core.config import get_settings

    return replace(get_settings(), kg_gate_enabled=False)


def test_analyze_kg_context_node_bypasses_gate_when_disabled():
    """KG_GATE_ENABLED=false면 매칭이 없어도 경량 종료하지 않고 뒷단으로 넘긴다."""
    with (
        patch(
            "app.multi_agent.kg.node.resolve_kg_context",
            return_value=NO_MATCH_RESPONSE,
        ),
        patch(
            "app.multi_agent.kg.node.get_settings",
            return_value=_bypass_settings(),
        ),
    ):
        result = analyze_kg_context_node(
            {
                "country": "US",
                "affected_materials": ["lithium"],
                "warnings": ["기존 경고"],
            },
        )

    # 사실은 그대로 남긴다 — KG가 확정한 게 아니라는 것을 나중에 구분할 수 있어야 한다.
    assert result["kg_shortage_detected"] is False
    assert result["kg_gate_bypassed"] is True
    # 경량 종료 결과를 채우지 않아야 supervisor가 erp로 이어간다.
    assert "briefing" not in result
    assert "procurement_risk_level" not in result
    # 기존 경고를 덮어쓰지 않고 뒤에 붙인다.
    assert result["warnings"][0] == "기존 경고"
    assert "KG_GATE_ENABLED=false" in result["warnings"][1]


def test_supervisor_continues_after_gate_bypass():
    """우회 플래그가 있으면 supervisor가 조기 종료(finish) 대신 erp로 보낸다."""
    from app.multi_agent.nodes.supervisor_node import select_next_route

    base = {
        "kg_context": NO_MATCH_RESPONSE,
        "kg_shortage_detected": False,
    }

    assert select_next_route(base) == "finish"
    assert select_next_route({**base, "kg_gate_bypassed": True}) == "erp"
