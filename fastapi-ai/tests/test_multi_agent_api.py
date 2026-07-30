from fastapi.testclient import TestClient

from app.api.dependencies import (
    get_multi_agent_graph,
)
from app.main import app


client = TestClient(app)


class FakeMultiAgentGraph:
    def __init__(self):
        self.received_state = None

    def invoke(self, state):
        self.received_state = state

        return {
            **state,
            "procurement_risk_level": "critical",
            "procurement_risk_score": 72,
            "risk_reasons": [
                "ERP 내부 노출도 점수: 100",
            ],
            "erp_assessment": {
                "erp_exposure_score": 100,
                "findings": [
                    "입고 전에 재고가 소진될 수 있습니다.",
                ],
            },
            "erp_reassessment": {
                "score_before": 100,
                "score_after": 100,
            },
            "contract_assessment": {
                "contract_gap_score": 30,
            },
            "contract_findings": [
                {
                    "contract_id": 1001,
                    "page": 1,
                    "evidence_text": "납기 지연 통보 조항",
                },
            ],
            "recommended_actions": [
                "기존 발주의 납기와 수량을 확인합니다.",
            ],
            "briefing": "구매 위험 브리핑입니다.",
            "llm_used": False,
            "llm_error": None,
            "review_passed": True,
            "warnings": [],
        }


def test_creates_multi_agent_briefing_with_camel_case():
    fake_graph = FakeMultiAgentGraph()

    app.dependency_overrides[
        get_multi_agent_graph
    ] = lambda: fake_graph

    try:
        response = client.post(
            "/api/v1/internal/multi-agent/briefings",
            json={
                "newsId": "news-api-001",
                "title": "항만 파업으로 니켈 선적 지연",
                "summaryKr": (
                    "니켈 공급 일정에 차질이 예상됩니다."
                ),
                "impactDomainFinal": "logistics",
                "affectedMaterials": ["Nickel"],
                "externalSignalLevel": "warning",
                "externalSignalScore": 60,
                "erpContext": {
                    "inventoryDays": 18,
                },
                "ragContractId": 1001,
                "ragSupplierId": 2001,
                "ragMaterialId": 3001,
                "useLlm": False,
            },
        )
    finally:
        app.dependency_overrides.pop(
            get_multi_agent_graph,
            None,
        )

    assert response.status_code == 200

    body = response.json()

    assert body["success"] is True
    assert body["data"]["newsId"] == "news-api-001"
    assert (
        body["data"]["procurementRiskLevel"]
        == "critical"
    )
    assert (
        body["data"]["procurementRiskScore"]
        == 72
    )
    assert body["data"]["reviewPassed"] is True

    # API에서는 camelCase를 받지만
    # LangGraph 내부에는 snake_case로 전달된다.
    assert (
        fake_graph.received_state["news_id"]
        == "news-api-001"
    )
    assert (
        fake_graph.received_state[
            "external_signal_score"
        ]
        == 60
    )
    assert fake_graph.received_state[
        "retry_count"
    ] == 0


def test_rejects_invalid_external_signal_score():
    response = client.post(
        "/api/v1/internal/multi-agent/briefings",
        json={
            "newsId": "news-api-002",
            "title": "잘못된 점수 요청",
            "impactDomainFinal": "logistics",
            "externalSignalScore": 150,
            "erpContext": {},
        },
    )

    assert response.status_code == 422
    assert response.json()["success"] is False