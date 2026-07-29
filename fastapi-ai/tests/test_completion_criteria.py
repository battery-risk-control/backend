from copy import deepcopy

from fastapi.testclient import TestClient

from app.main import app
from app.schemas.analyze import FeatureVector
from app.services.classification_service import ClassificationService
from app.services.severity_service import SeverityService

client = TestClient(app)

BASE_EVENT = {
    "external_event_id": "EVENT-100",
    "title": "Heavy rain disrupts lithium production",
    "content": "Flooding stopped lithium operations in Chile.",
    "source_name": "TEST",
    "published_at": "2026-07-20T07:30:00+09:00",
    "country_code": "CL",
}


def test_extraction_mock_changes_with_input() -> None:
    cases = [
        ("Nickel workers strike", "니켈 파업", "NICKEL", "STRIKE", "LOGISTICS"),
        ("Cobalt export ban", "코발트 수출 제한", "COBALT", "EXPORT_RESTRICTION", "POLICY"),
        ("Lithium price volatility", "리튬 가격 변동성", "LITHIUM", "MARKET_VOLATILITY", "MARKET"),
    ]
    for title, content, material, event_type, domain in cases:
        response = client.post("/api/v1/internal/llm/extract", json={"title": title, "content": content, "country_code": "CL"})
        data = response.json()["data"]
        assert material in data["affected_materials"]
        assert data["event_type"] == event_type
        assert data["impact_domain_draft"] == domain
        assert data["mock"] is True


def _features(**changes) -> FeatureVector:
    values = dict(goldstein_scale=0.0, news_count=1, country_is_mining_hub=False,
                  rainfall_24h_mm=0.0, gdacs_alert_level=0, actor1_type="UNKNOWN",
                  actor2_type="UNKNOWN", stock_volatility_20d=0.0)
    values.update(changes)
    return FeatureVector(**values)


def test_classification_mock_supports_multiple_domains() -> None:
    service = ClassificationService()
    assert service.classify(_features(rainfall_24h_mm=120)).impact_domain == "PRODUCTION"
    assert service.classify(_features(actor1_type="LAB")).impact_domain == "LOGISTICS"
    assert service.classify(_features(actor1_type="POLICY")).impact_domain == "POLICY"
    assert service.classify(_features(goldstein_scale=-7)).impact_domain == "GEOPOLITICS"
    assert service.classify(_features()).impact_domain == "MARKET"


def test_severity_mock_supports_all_three_levels() -> None:
    """F3 심각도는 외부 데이터(뉴스·기상·시장)만으로 계산됩니다 — ERP 데이터는 관여하지 않습니다.

    2026-07-27: article_signal(tone×log(news_count)) 곱셈 공식 + GDACS 하드게이트로 교체.
    gdacs_alert_level<2는 base_score에 기여하지 않고, >=2면 base_score와 무관하게
    무조건 CRITICAL_MIN(75) 이상으로 끌어올립니다.
    """
    service = SeverityService()
    assert service.score(_features()).severity == "NORMAL"
    assert service.score(_features(goldstein_scale=-10)).severity == "WARNING"
    critical = service.score(_features(gdacs_alert_level=2, goldstein_scale=-7))
    assert critical.severity == "CRITICAL"
    assert critical.calculation_details


def test_analyze_options_control_all_optional_steps() -> None:
    payload = {"event": deepcopy(BASE_EVENT), "options": {
        "enrich_features": False, "include_erp_context": False,
        "include_contract_rag": False, "generate_briefing": False,
    }}
    data = client.post("/api/v1/analyze", json=payload).json()["data"]
    assert data["feature_enrichment_applied"] is False
    assert data["features"]["news_count"] == 0
    assert data["erp_context_included"] is False
    assert data["matched_entities"]["supplier_ids"] == []
    assert data["contract_rag_included"] is False
    assert data["briefing_id"] is None


def test_analyze_options_enable_all_mock_steps() -> None:
    data = client.post("/api/v1/analyze", json={"event": deepcopy(BASE_EVENT)}).json()["data"]
    assert data["feature_enrichment_applied"] is True
    assert data["features"]["news_count"] == 15
    assert data["erp_context_included"] is True
    assert data["matched_entities"]["supplier_ids"] == [11]
    assert data["contract_rag_included"] is True
    assert data["briefing_id"] == 7001


def test_rag_upload_search_filters_and_test_isolation() -> None:
    uploads = [
        (501, 11, "supplier 11 price escalation threshold ten percent"),
        (502, 12, "supplier 12 force majeure clause"),
    ]
    for contract_id, supplier_id, text in uploads:
        response = client.post(
            "/api/v1/rag/contracts",
            files={"file": (f"{contract_id}.txt", text.encode(), "text/plain")},
            data={"contract_id": str(contract_id), "supplier_id": str(supplier_id), "material_id": "1", "document_type": "LTA"},
        )
        assert response.status_code == 200
        assert response.json()["data"]["chunk_count"] >= 1

    response = client.post("/api/v1/rag/search", json={"query": "price", "filters": {"supplier_id": 11}, "top_k": 5})
    results = response.json()["data"]["results"]
    assert len(results) == 1
    assert results[0]["contract_id"] == 501
    assert results[0]["supplier_id"] == 11
    assert results[0]["page_number"] == 1


def test_rag_rejects_unsupported_file_type_with_common_error() -> None:
    response = client.post(
        "/api/v1/rag/contracts",
        files={"file": ("contract.docx", b"content", "application/octet-stream")},
        data={"contract_id": "501", "supplier_id": "11", "material_id": "1"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_DOCUMENT_TYPE"


def test_invalid_pdf_is_rejected_by_real_parser() -> None:
    response = client.post(
        "/api/v1/rag/contracts",
        files={"file": ("contract.pdf", b"%PDF-1.4 mock", "application/pdf")},
        data={"contract_id": "503", "supplier_id": "13", "material_id": "2"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_PDF"
    search = client.post("/api/v1/rag/search", json={"query": "contract", "filters": {"contract_id": 503}})
    assert search.json()["data"]["results"] == []


def test_all_seven_apis_have_concrete_openapi_contracts() -> None:
    schema = client.get("/openapi.json").json()
    paths = {
        "/api/v1/analyze", "/api/v1/rag/contracts", "/api/v1/rag/search",
        "/api/v1/internal/llm/extract", "/api/v1/internal/ml/classify",
        "/api/v1/internal/severity/score", "/api/v1/internal/briefings",
    }
    assert paths <= schema["paths"].keys()
    for path in paths:
        operation = schema["paths"][path]["post"]
        assert "requestBody" in operation
        success_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
        assert "$ref" in success_schema
        assert "dict" not in success_schema["$ref"].lower()
        assert "422" in operation["responses"]
    component_names = schema["components"]["schemas"].keys()
    assert any("ExtractionResult" in name for name in component_names)
    assert any("ClassificationResult" in name for name in component_names)
    assert any("SeverityResult" in name for name in component_names)
    assert any("BriefingGenerationResult" in name for name in component_names)


def test_docs_html_is_available() -> None:
    response = client.get("/docs")
    assert response.status_code == 200
    assert "swagger-ui" in response.text.lower()


def test_unexpected_error_uses_common_error_envelope() -> None:
    from app.api.dependencies import get_extraction_service

    class BrokenExtractionService:
        def extract(self, _event):
            raise RuntimeError("secret implementation detail")

    app.dependency_overrides[get_extraction_service] = lambda: BrokenExtractionService()
    safe_client = TestClient(app, raise_server_exceptions=False)
    try:
        response = safe_client.post("/api/v1/internal/llm/extract", json={"title": "test", "content": "test"})
        assert response.status_code == 500
        assert response.json()["error"]["code"] == "INTERNAL_SERVER_ERROR"
        assert "secret" not in response.text
    finally:
        app.dependency_overrides.clear()
