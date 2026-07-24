from app.llm.response_generator import (
    build_response_payload,
)


def test_build_response_payload_uses_only_required_data():
    state = {
        "news_id": "news-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "article_text": "전체 기사 원문",
        "summary_kr": "니켈 공급 지연 가능성이 있습니다.",
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"],

        "procurement_risk_level": "critical",
        "procurement_risk_score": 72,
        "risk_reasons": [
            "입고 전에 재고가 소진될 수 있습니다."
        ],

        "erp_context": {
            "internal_secret": "LLM에 보내면 안 되는 원본"
        },

        "erp_assessment": {
            "erp_exposure_score": 100,
            "findings": [
                "대체 공급사가 없습니다."
            ],
        },

        "erp_reassessment": {
            "score_before": 100,
            "score_after": 100,
            "requires_human_confirmation": True,
        },

        "contract_assessment": {
            "contract_gap_score": 30,
            "protection_status": "partial",
        },

        "contract_findings": [
            {
                "contract_id": 1001,
                "page": 3,
                "evidence_text": (
                    "공급자는 납기 변경 일정을 통보해야 한다."
                ),
            }
        ],
    }

    payload = build_response_payload(state)

    assert payload["news"]["news_id"] == "news-001"
    assert payload["procurement_risk"]["score"] == 72

    assert (
        payload["erp_assessment"]["erp_exposure_score"]
        == 100
    )

    assert (
        payload["contract_findings"][0]["page"]
        == 3
    )

    # 전체 기사 원문과 ERP 원본은 LLM 입력에서 제외
    assert "article_text" not in payload["news"]
    assert "erp_context" not in payload
    assert "internal_secret" not in str(payload)
