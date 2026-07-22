from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_analyze_mock_response() -> None:
    response = client.post(
        "/api/v1/analyze",
        json={
            "event": {
                "external_event_id": "GDELT-123456",
                "title": "Heavy rainfall disrupts lithium production in Chile",
                "content": "Heavy rainfall has disrupted operations.",
                "source_name": "GDELT",
                "source_url": "https://example.com/news/123456",
                "published_at": "2026-07-20T07:30:00+09:00",
                "country_code": "CL"
            },
            "options": {
                "enrich_features": True,
                "include_erp_context": True,
                "include_contract_rag": True,
                "generate_briefing": True
            }
        }
    )
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["classification"]["impact_domain"] == "PRODUCTION"
    assert body["data"]["severity"]["severity"] == "CRITICAL"


def test_analyze_applies_feature_overrides() -> None:
    response = client.post("/api/v1/analyze", json={
        "event": {
            "external_event_id": "EVENT-1",
            "title": "Event",
            "content": "Content",
            "source_name": "TEST",
            "published_at": "2026-07-20T07:30:00+09:00"
        },
        "feature_overrides": {"news_count": 3, "rainfall_24h_mm": 10.5}
    })
    assert response.status_code == 200
    assert response.json()["data"]["features"]["news_count"] == 3
    assert response.json()["data"]["features"]["rainfall_24h_mm"] == 10.5


def test_validation_error_uses_common_envelope() -> None:
    response = client.post("/api/v1/analyze", json={})
    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert "timestamp" in body
