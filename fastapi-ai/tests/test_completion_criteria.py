from copy import deepcopy

from fastapi.testclient import TestClient

from app.main import app
from app.schemas.analyze import FeatureVector
from app.services.classification_service import ClassificationService
from app.services.severity_service import SeverityService

client = TestClient(app)

BASE_EVENT = {
    "externalEventId": "EVENT-100",
    "title": "Heavy rain disrupts lithium production",
    "content": "Flooding stopped lithium operations in Chile.",
    "sourceName": "TEST",
    "publishedAt": "2026-07-20T07:30:00+09:00",
    "countryCode": "CL",
}


def test_extraction_mock_changes_with_input() -> None:
    cases = [
        ("Nickel workers strike", "니켈 파업", "NICKEL", "STRIKE", "LOGISTICS"),
        ("Cobalt export ban", "코발트 수출 제한", "COBALT", "EXPORT_RESTRICTION", "POLICY"),
        ("Lithium price volatility", "리튬 가격 변동성", "LITHIUM", "MARKET_VOLATILITY", "MARKET"),
    ]
    for title, content, material, event_type, domain in cases:
        response = client.post("/api/v1/internal/llm/extract", json={"title": title, "content": content, "countryCode": "CL"})
        data = response.json()["data"]
        assert material in data["affectedMaterials"]
        assert data["eventType"] == event_type
        assert data["impactDomainDraft"] == domain
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
    service = SeverityService()
    assert service.score(_features(), None).severity == "NORMAL"
    assert service.score(_features(gdacs_alert_level=1), None).severity == "WARNING"
    assert service.score(_features(gdacs_alert_level=2, goldstein_scale=-7), None).severity == "WARNING"
    from app.repositories.erp_repository import InMemoryErpRepository
    context = InMemoryErpRepository().find_context("CL", ["LITHIUM"])
    critical = service.score(_features(gdacs_alert_level=2, goldstein_scale=-7), context)
    assert critical.severity == "CRITICAL"
    assert critical.calculation_details


def test_analyze_options_control_all_optional_steps() -> None:
    payload = {"event": deepcopy(BASE_EVENT), "options": {
        "enrichFeatures": False, "includeErpContext": False,
        "includeContractRag": False, "generateBriefing": False,
    }}
    data = client.post("/api/v1/analyze", json=payload).json()["data"]
    assert data["featureEnrichmentApplied"] is False
    assert data["features"]["newsCount"] == 0
    assert data["erpContextIncluded"] is False
    assert data["matchedEntities"]["supplierIds"] == []
    assert data["contractRagIncluded"] is False
    assert data["briefingId"] is None


def test_analyze_options_enable_all_mock_steps() -> None:
    data = client.post("/api/v1/analyze", json={"event": deepcopy(BASE_EVENT)}).json()["data"]
    assert data["featureEnrichmentApplied"] is True
    assert data["features"]["newsCount"] == 15
    assert data["erpContextIncluded"] is True
    assert data["matchedEntities"]["supplierIds"] == [11]
    assert data["contractRagIncluded"] is True
    assert data["briefingId"] == 7001


def test_rag_upload_search_filters_and_test_isolation() -> None:
    uploads = [
        (501, 11, "supplier 11 price escalation threshold ten percent"),
        (502, 12, "supplier 12 force majeure clause"),
    ]
    for contract_id, supplier_id, text in uploads:
        response = client.post(
            "/api/v1/rag/contracts",
            files={"file": (f"{contract_id}.txt", text.encode(), "text/plain")},
            data={"contractId": str(contract_id), "supplierId": str(supplier_id), "materialId": "1", "documentType": "LTA"},
        )
        assert response.status_code == 200
        assert response.json()["data"]["chunkCount"] >= 1

    response = client.post("/api/v1/rag/search", json={"query": "price", "filters": {"supplierId": 11}, "topK": 5})
    results = response.json()["data"]["results"]
    assert len(results) == 1
    assert results[0]["contractId"] == 501
    assert results[0]["supplierId"] == 11
    assert results[0]["pageNumber"] == 1


def test_rag_rejects_unsupported_file_type_with_common_error() -> None:
    response = client.post(
        "/api/v1/rag/contracts",
        files={"file": ("contract.docx", b"content", "application/octet-stream")},
        data={"contractId": "501", "supplierId": "11", "materialId": "1"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_DOCUMENT_TYPE"


def test_pdf_mock_upload_uses_placeholder_chunk_until_parser_is_connected() -> None:
    response = client.post(
        "/api/v1/rag/contracts",
        files={"file": ("contract.pdf", b"%PDF-1.4 mock", "application/pdf")},
        data={"contractId": "503", "supplierId": "13", "materialId": "2"},
    )
    assert response.status_code == 200
    assert response.json()["data"]["chunkCount"] == 1
    search = client.post("/api/v1/rag/search", json={"query": "contract", "filters": {"contractId": 503}})
    assert search.json()["data"]["results"][0]["pageNumber"] == 1


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
