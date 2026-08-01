from dataclasses import dataclass

from app.multi_agent.agents.outbound_contract_agent import (
    analyze_outbound_contract_node,
)


@dataclass
class FakeSearchResult:
    document_id: str
    contract_id: int
    supplier_id: int | None
    material_id: int | None
    page_number: int
    content: str
    similarity_score: float


class FakeOutboundRagService:
    def __init__(self):
        self.last_call = None

    def search(
        self,
        query,
        contract_id,
        supplier_id,
        top_k,
        material_id=None,
        product_id=None,
        customer_id=None,
    ):
        self.last_call = {
            "query": query,
            "contract_id": contract_id,
            "supplier_id": supplier_id,
            "material_id": material_id,
            "product_id": product_id,
            "customer_id": customer_id,
            "top_k": top_k,
        }
        return [
            FakeSearchResult(
                document_id="OUT-DOC-001",
                contract_id=contract_id,
                supplier_id=None,
                material_id=None,
                page_number=4,
                content="납품 지연 시 지체상금을 지급한다.",
                similarity_score=0.88,
            ),
        ]


def test_outbound_contract_id_missing_skips_search():
    state = {}

    result = analyze_outbound_contract_node(
        state,
        service=FakeOutboundRagService(),
    )

    assert result["outbound_contract_findings"] == []
    assert result["outbound_contract_checked"] is True


def test_outbound_contract_search_uses_product_and_customer_id():
    service = FakeOutboundRagService()
    state = {
        "outbound_contracts": [
            {"contract_id": 501, "product_id": 601, "customer_id": 701},
        ],
    }

    result = analyze_outbound_contract_node(
        state,
        service=service,
    )

    assert result["outbound_contract_checked"] is True
    assert len(result["outbound_contract_findings"]) == 1
    assert (
        result["outbound_contract_findings"][0]["contract_id"] == 501
    )

    # 인바운드(supplier_id/material_id)가 아니라 아웃바운드(product_id/customer_id)로
    # 검색해야 한다 — 인바운드 contracts 테이블과 PK가 우연히 겹쳐도 섞이지 않도록.
    assert service.last_call["contract_id"] == 501
    assert service.last_call["product_id"] == 601
    assert service.last_call["customer_id"] == 701
    assert service.last_call["supplier_id"] is None
    assert service.last_call["material_id"] is None


def test_outbound_contract_search_covers_every_contract_in_list():
    """상위 N건으로 추린 아웃바운드 계약이 여러 건이면 전부 검색해서 findings를 합쳐야 한다."""

    service = FakeOutboundRagService()
    state = {
        "outbound_contracts": [
            {"contract_id": 501, "product_id": 601, "customer_id": 701},
            {"contract_id": 502, "product_id": 602, "customer_id": 702},
            {"contract_id": 503, "product_id": 603, "customer_id": 703},
        ],
    }

    result = analyze_outbound_contract_node(
        state,
        service=service,
    )

    assert result["outbound_contract_checked"] is True
    found_contract_ids = {
        finding["contract_id"]
        for finding in result["outbound_contract_findings"]
    }
    assert found_contract_ids == {501, 502, 503}
