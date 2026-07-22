from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

FEATURES = {
    "goldstein_scale": -7.2,
    "news_count": 15,
    "country_is_mining_hub": True,
    "rainfall_24h_mm": 230.0,
    "gdacs_alert_level": 2,
    "actor1_type": "GOV",
    "actor2_type": "COM",
    "stock_volatility_20d": 0.041,
}

def test_internal_classify():
    response = client.post("/api/v1/internal/ml/classify", json={"features": FEATURES})
    assert response.status_code == 200
    assert response.json()["data"]["impact_domain"] == "PRODUCTION"

def test_internal_severity():
    response = client.post("/api/v1/internal/severity/score", json={
        "features": FEATURES, "stock_days": 12, "feoc_status": False
    })
    assert response.status_code == 200
    assert response.json()["data"]["severity"] == "CRITICAL"

def test_rag_filter_required():
    response = client.post("/api/v1/rag/search", json={
        "query": "가격 조정 조건", "filters": {}, "top_k": 5
    })
    assert response.status_code == 422
