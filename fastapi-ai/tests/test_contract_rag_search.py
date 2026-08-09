"""계약·RAG 화면 전용 검색(`/api/v1/contract-rag/search`) 테스트.

핵심은 **필터 없이도 검색된다**는 점이다 — 기존 `/api/v1/rag/search`는 필터를 강제하므로
(멀티에이전트 계약) 그 규칙이 그대로 남아 있는지도 같이 확인한다.
"""

import pytest
from fastapi.testclient import TestClient

from app.api.v1.contract_rag import router  # noqa: F401  (라우터 등록 확인용)
from app.main import app
from app.services.contract_rag_service import (
    ContractChunkHit,
    ContractRagSearchService,
    _cosine_similarity,
    get_contract_rag_search_service,
)

client = TestClient(app)


class _StubCollection:
    def __init__(self) -> None:
        self.last_where: object = "not-called"

    def query(self, *, query_embeddings, n_results, where, include):
        self.last_where = where
        return {
            "documents": [["Article 4\nDELIVERY AND PENALTY\n\n4.02 Delay Penalty..."]],
            "metadatas": [[{
                "document_id": "con_abc",
                "contract_id": 11,
                "document_type": "CONTRACT",
                "chunk_index": 7,
                "page_number": 1,
                "content_hash": "a" * 64,
                "supplier_id": 3,
                "material_id": 5,
            }]],
            "distances": [[0.42]],
        }


class _StubClient:
    def __init__(self, collection: _StubCollection) -> None:
        self._collection = collection

    def get_or_create_collection(self, **_kwargs):
        return self._collection


@pytest.fixture
def stub_service():
    get_contract_rag_search_service.cache_clear()
    collection = _StubCollection()
    service = ContractRagSearchService(client=_StubClient(collection))
    yield service, collection
    get_contract_rag_search_service.cache_clear()


def test_search_without_filter_scans_whole_collection(stub_service) -> None:
    service, collection = stub_service
    hits = service.search("납기 지연 위약금", top_k=3)

    # 필터가 없으면 where 자체를 넘기지 않아야 컬렉션 전체가 검색 대상이 된다.
    assert collection.last_where is None
    assert hits[0].contract_id == 11
    assert hits[0].chunk_index == 7
    assert hits[0].similarity_score == pytest.approx(0.58)


def test_search_with_single_filter_narrows_to_that_contract(stub_service) -> None:
    service, collection = stub_service
    service.search("납기", contract_id=11)
    assert collection.last_where == {"contract_id": 11}


def test_search_with_multiple_filters_uses_and(stub_service) -> None:
    service, collection = stub_service
    service.search("납기", contract_id=11, material_id=5)
    assert collection.last_where == {"$and": [{"contract_id": 11}, {"material_id": 5}]}


def test_kind_inbound_filters_to_chunks_that_have_supplier_id(stub_service) -> None:
    # 인바운드 청크에만 supplier_id 키가 있으므로, 존재필터 하나로 매입 계약만 골라낸다.
    service, collection = stub_service
    service.search("납기", kind="INBOUND")
    assert collection.last_where == {"supplier_id": {"$gte": 0}}


def test_kind_outbound_filters_to_chunks_that_have_product_id(stub_service) -> None:
    service, collection = stub_service
    service.search("배상책임", kind="OUTBOUND")
    assert collection.last_where == {"product_id": {"$gte": 0}}


def test_kind_all_does_not_add_existence_filter(stub_service) -> None:
    service, collection = stub_service
    service.search("납기", kind="ALL")
    assert collection.last_where is None


def test_specific_outbound_contract_uses_product_and_customer(stub_service) -> None:
    # 특정 납품계약은 product_id+customer_id로 콕 집는다(contract_id는 인바운드와 번호가 겹침).
    # id를 이미 줬으면 kind 존재필터는 겹치지 않는다.
    service, collection = stub_service
    service.search("배상책임", kind="OUTBOUND", product_id=4, customer_id=1)
    assert collection.last_where == {"$and": [{"product_id": 4}, {"customer_id": 1}]}


def test_blank_query_is_rejected(stub_service) -> None:
    service, _collection = stub_service
    with pytest.raises(Exception):
        service.search("   ")


def test_cosine_distance_becomes_similarity() -> None:
    assert _cosine_similarity(0.0) == 1.0
    assert _cosine_similarity(0.39) == pytest.approx(0.61)
    # 거리가 1을 넘어도(코사인 거리는 0~2) 유사도는 0 밑으로 내려가지 않는다.
    assert _cosine_similarity(1.5) == 0.0


class _StubSearchService:
    embedding_type = "OPENAI_API"
    embedding_version = "openai-text-embedding-3-large"
    mock_embedding = False
    collection_name = "contract_documents_openai_v1"

    def search(self, query, *, kind="ALL", contract_id=None, supplier_id=None,
               material_id=None, product_id=None, customer_id=None, top_k=5):
        return [ContractChunkHit(
            document_id="con_abc", contract_id=11, document_type="CONTRACT",
            chunk_index=7, page_number=1, content="Article 4\nDELIVERY AND PENALTY",
            content_hash="b" * 64, similarity_score=0.61, supplier_id=3, material_id=5,
            product_id=None, customer_id=None,
        )]


def test_api_returns_snake_case_and_scope() -> None:
    app.dependency_overrides[get_contract_rag_search_service] = _StubSearchService
    try:
        response = client.post(
            "/api/v1/contract-rag/search", json={"query": "납기 지연", "top_k": 5})
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["scope"] == "all"
        assert data["mock"] is False
        assert data["embedding_type"] == "OPENAI_API"
        assert data["results"][0]["contract_id"] == 11
        assert data["results"][0]["similarity_score"] == 0.61

        filtered = client.post(
            "/api/v1/contract-rag/search", json={"query": "납기 지연", "contract_id": 11})
        assert filtered.json()["data"]["scope"] == "filtered"
    finally:
        app.dependency_overrides.pop(get_contract_rag_search_service, None)


def test_multi_agent_search_still_requires_a_filter() -> None:
    """기존 검색 규칙을 건드리지 않았는지 확인한다."""
    response = client.post("/api/v1/rag/search", json={"query": "가격 조정", "filters": {}})
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "RAG_FILTER_REQUIRED"
