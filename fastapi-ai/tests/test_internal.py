from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

FEATURES = {
    "goldsteinScale": -7.2,
    "newsCount": 15,
    "countryIsMiningHub": True,
    "rainfall24hMm": 230.0,
    "gdacsAlertLevel": 2,
    "actor1Type": "GOV",
    "actor2Type": "COM",
    "stockVolatility20d": 0.041,
}

def test_internal_classify():
    response = client.post("/api/v1/internal/ml/classify", json={"features": FEATURES})
    assert response.status_code == 200
    assert response.json()["data"]["impactDomain"] == "PRODUCTION"

def test_internal_severity():
    response = client.post("/api/v1/internal/severity/score", json={
        "features": FEATURES, "stockDays": 12, "feocStatus": False
    })
    assert response.status_code == 200
    assert response.json()["data"]["severity"] == "CRITICAL"

def test_rag_filter_required():
    response = client.post("/api/v1/rag/search", json={
        "query": "가격 조정 조건", "filters": {}, "topK": 5
    })
    assert response.status_code == 422
