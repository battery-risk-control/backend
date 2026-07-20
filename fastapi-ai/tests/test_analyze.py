from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_analyze_mock_response() -> None:
    response = client.post(
        "/api/v1/analyze",
        json={
            "event": {
                "externalEventId": "GDELT-123456",
                "title": "Heavy rainfall disrupts lithium production in Chile",
                "content": "Heavy rainfall has disrupted operations.",
                "sourceName": "GDELT",
                "sourceUrl": "https://example.com/news/123456",
                "publishedAt": "2026-07-20T07:30:00+09:00",
                "countryCode": "CL"
            },
            "options": {
                "enrichFeatures": True,
                "includeErpContext": True,
                "includeContractRag": True,
                "generateBriefing": True
            }
        }
    )
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["classification"]["impactDomain"] == "PRODUCTION"
    assert body["data"]["severity"]["severity"] == "CRITICAL"


def test_analyze_applies_feature_overrides() -> None:
    response = client.post("/api/v1/analyze", json={
        "event": {
            "externalEventId": "EVENT-1",
            "title": "Event",
            "content": "Content",
            "sourceName": "TEST",
            "publishedAt": "2026-07-20T07:30:00+09:00"
        },
        "featureOverrides": {"newsCount": 3, "rainfall24hMm": 10.5}
    })
    assert response.status_code == 200
    assert response.json()["data"]["features"]["newsCount"] == 3
    assert response.json()["data"]["features"]["rainfall24hMm"] == 10.5


def test_validation_error_uses_common_envelope() -> None:
    response = client.post("/api/v1/analyze", json={})
    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert "timestamp" in body
