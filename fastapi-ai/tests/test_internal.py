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
        "inventory_days": 12,
        "safety_stock_days": 20,
        "expected_supply_gap_days": 8,
        "supplier_dependency_ratio": 0.72,
        "price_change_rate": 11.5,
        "logistics_delay_days": 7,
        "gdacs_alert_level": 2,
        "feoc_status": "NO",
        "data_quality_status": "VALID",
    })
    assert response.status_code == 200
    assert response.json()["data"]["severity"] == "CRITICAL"

def test_rag_filter_required():
    response = client.post("/api/v1/rag/search", json={
        "query": "가격 조정 조건", "filters": {}, "top_k": 5
    })
    assert response.status_code == 422
