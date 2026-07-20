from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_rag_search_requires_contract_or_supplier() -> None:
    response = client.post("/api/v1/rag/search", json={"query": "가격 조정", "filters": {}})
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "RAG_FILTER_REQUIRED"


def test_rag_search_validates_top_k() -> None:
    response = client.post("/api/v1/rag/search", json={
        "query": "가격 조정", "filters": {"contractId": 501}, "topK": 21
    })
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_rag_search_returns_camel_case_results() -> None:
    client.post(
        "/api/v1/rag/contracts",
        files={"file": ("contract.txt", b"price escalation clause", "text/plain")},
        data={"contractId": "501", "supplierId": "11", "materialId": "1", "documentType": "LTA"},
    )
    response = client.post("/api/v1/rag/search", json={
        "query": "가격 조정", "filters": {"contractId": 501}
    })
    assert response.status_code == 200
    assert response.json()["data"]["results"][0]["contractId"] == 501


def test_internal_mock_endpoints_are_documented_and_callable() -> None:
    cases = [
        ("/api/v1/internal/llm/extract", {"title": "폭우", "content": "생산 차질"}),
        ("/api/v1/internal/ml/classify", {"features": {}}),
        ("/api/v1/internal/severity/score", {"features": {}}),
        ("/api/v1/internal/briefings", {"riskId": 101}),
    ]
    for path, payload in cases:
        response = client.post(path, json=payload)
        assert response.status_code == 200
        assert response.json()["success"] is True
        assert response.json()["data"]["mock"] is True


def test_openapi_contains_priority_one_endpoints() -> None:
    paths = client.get("/openapi.json").json()["paths"]
    assert "/api/v1/analyze" in paths
    assert "/api/v1/internal/ml/classify" in paths
    assert "/api/v1/internal/severity/score" in paths
