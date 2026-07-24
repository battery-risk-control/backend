from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_health_api():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_create_briefing_api():
    response = client.post(
        "/api/v1/briefings",
        json={
            "news_id": "news-briefing-001",
            "title": "항만 파업으로 니켈 선적 지연",
            "article_text": (
                "항만 파업으로 니켈 선적이 지연되고 있다."
            ),
            "summary_kr": (
                "항만 파업으로 니켈 공급 차질이 예상된다."
            ),
            "impact_domain_draft": "logistics",
            "impact_domain_final": "logistics",
            "external_signal_level": "warning",
            "external_signal_score": 60,
            "affected_materials": ["Nickel"],
            "erp_context": {
                "materialContext": {
                    "materialId": "MAT-NI-SULF",
                    "materialName": "Nickel Sulfate",
                    "unit": "KG",
                    "onHandQuantity": 18000,
                    "reservedQuantity": 0,
                    "blockedQuantity": 0,
                    "qualityHoldQuantity": 0,
                    "averageDailyUsage": 1000,
                    "safetyStockQuantity": 20000,
                    "supplierDependencyRatio": 0.80,
                    "purchaseOrderStatus": "DELAYED",
                    "alternativeSupplierStatus": "NONE",
                    "supplierStatus": "ACTIVE",
                    "primarySupplierId": "SUP-NICKEL-01",
                    "primaryContractId": "CTR-001",
                    "inventorySnapshotAt": (
                        "2026-07-22T08:00:00+09:00"
                    ),
                },
                "purchaseOrders": [
                    {
                        "purchaseOrderItemId": "POI-001",
                        "purchaseOrderId": "PO-001",
                        "materialId": "MAT-NI-SULF",
                        "supplierId": "SUP-NICKEL-01",
                        "contractId": "CTR-001",
                        "remainingQuantity": 5000,
                        "orderStatus": "DELAYED",
                        "effectiveArrivalDate": "2026-08-16",
                        "eligibleForEta": True,
                    },
                    {
                        "purchaseOrderItemId": "POI-002",
                        "purchaseOrderId": "PO-002",
                        "materialId": "MAT-NI-SULF",
                        "supplierId": "SUP-NICKEL-01",
                        "contractId": "CTR-001",
                        "remainingQuantity": 3000,
                        "orderStatus": "CONFIRMED",
                        "effectiveArrivalDate": "2026-08-20",
                        "eligibleForEta": True,
                    },
                ],
                "alternativeSuppliers": [],
            },
        },
    )

    assert response.status_code == 200
    body = response.json()

    assert body["news_id"] == "news-briefing-001"
    assert body["impact_domain_final"] == "logistics"
    assert body["procurement_risk_score"] == 72
    assert body["procurement_risk_level"] == "critical"
    assert body["erp_assessment"]["erp_exposure_score"] == 100
    assert (
        body["erp_assessment"]["expected_supply_gap_days"]
        == "7"
)
    assert (
        body["erp_reassessment"]["score_before"]
        == 100
    )
    assert (
        body["erp_reassessment"]["score_after"]
        == 100
    )
    assert (
        body["erp_reassessment"][
            "requires_human_confirmation"
        ]
        is True
    )
    assert len(
        body["erp_reassessment"]["checked_questions"]
    ) == 1
    assert (
        body["contract_assessment"]["contract_gap_score"]
        == 30
    )
    assert body["llm_used"] is False
    assert body["llm_error"] is None
    assert body["review_passed"] is True
    assert body["warnings"] == []


def test_create_briefing_rejects_invalid_score():
    response = client.post(
        "/api/v1/briefings",
        json={
            "news_id": "news-briefing-002",
            "title": "잘못된 점수 테스트",
            "impact_domain_final": "logistics",
            "external_signal_level": "warning",
            "external_signal_score": 150,
        },
    )
    assert response.status_code == 422

def test_create_briefing_rejects_negative_erp_quantity():
    response = client.post(
        "/api/v1/briefings",
        json={
            "news_id": "news-briefing-003",
            "title": "잘못된 ERP 재고 테스트",
            "impact_domain_final": "logistics",
            "external_signal_level": "warning",
            "external_signal_score": 60,
            "erp_context": {
                "materialContext": {
                    "materialId": "MAT-NI-SULF",
                    "materialName": "Nickel Sulfate",
                    "unit": "KG",
                    "onHandQuantity": -1,
                    "reservedQuantity": 0,
                    "blockedQuantity": 0,
                    "qualityHoldQuantity": 0,
                    "averageDailyUsage": 1000,
                    "safetyStockQuantity": 20000,
                    "supplierDependencyRatio": 0.80,
                    "purchaseOrderStatus": "DELAYED",
                    "alternativeSupplierStatus": "NONE",
                    "supplierStatus": "ACTIVE",
                    "primarySupplierId": "SUP-NICKEL-01",
                    "primaryContractId": "CTR-001",
                    "inventorySnapshotAt": (
                        "2026-07-22T08:00:00+09:00"
                    ),
                }
            },
        },
    )

    assert response.status_code == 422
