from decimal import Decimal
from types import SimpleNamespace

import pytest

from app.multi_agent.llm.response_generator import (
    build_response_payload,
    generate_response_with_llm,
)
from app.multi_agent.llm.schemas import (
    ResponseAgentOutput,
)


class FakeMessages:
    def __init__(self, parsed_output):
        self.parsed_output = parsed_output
        self.last_request = None

    def parse(self, **kwargs):
        self.last_request = kwargs

        return SimpleNamespace(
            parsed_output=self.parsed_output,
        )


class FakeAnthropicClient:
    def __init__(self, parsed_output):
        self.messages = FakeMessages(parsed_output)


def create_state():
    return {
        "news_id": "news-001",
        "title": "항만 파업으로 니켈 선적 지연",
        "summary_kr": (
            "항만 파업으로 니켈 공급 일정에 "
            "차질이 예상됩니다."
        ),
        "impact_domain_final": "logistics",
        "affected_materials": ["Nickel"],
        "procurement_risk_level": "critical",
        "procurement_risk_score": 72,
        "risk_reasons": [
            "ERP 내부 노출도 점수: 100",
        ],
        "erp_assessment": {
            "erp_exposure_score": 100,
            "expected_supply_gap_days": Decimal("7"),
            "stockout_before_eta": True,
            "has_alternative_supplier": False,
        },
        "erp_reassessment": {
            "requires_human_confirmation": False,
        },
        "contract_assessment": {
            "contract_gap_score": 30,
        },
        "contract_findings": [
            {
                "contract_id": 1001,
                "page": 1,
                "evidence_text": (
                    "공급자는 지연 사유와 변경 "
                    "일정을 통보해야 한다."
                ),
            },
        ],
    }


def test_builds_minimum_response_payload():
    payload = build_response_payload(
        create_state(),
    )

    assert payload["news"]["news_id"] == "news-001"
    assert payload["procurement_risk"]["score"] == 72
    assert (
        payload["erp_assessment"][
            "expected_supply_gap_days"
        ]
        == Decimal("7")
    )
    assert (
        payload["contract_findings"][0][
            "contract_id"
        ]
        == 1001
    )
    assert (
    "공급 공백 기간"
    in payload["metric_semantics"][
        "expected_supply_gap_days"
    ]
    )


def test_generates_structured_llm_response(
    monkeypatch,
):
    parsed_output = ResponseAgentOutput(
        recommended_actions=[
            "기존 발주의 납기와 수량을 확인합니다.",
        ],
        briefing=(
            "니켈 공급 지연 가능성에 대한 "
            "구매 위험 브리핑입니다."
        ),
    )
    fake_client = FakeAnthropicClient(parsed_output)

    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_client",
        lambda: fake_client,
    )
    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_model",
        lambda: "claude-sonnet-5",
    )
    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_max_tokens",
        lambda: 4096,
    )

    result = generate_response_with_llm(
        create_state(),
    )

    assert (
    result["recommended_actions"]
    == parsed_output.recommended_actions
    )

    assert parsed_output.briefing in result["briefing"]

    assert (
        "final_risk_level: critical"
        in result["briefing"]
    )

    assert (
        "[contract_id: 1001, page: 1]"
        in result["briefing"]
    )
    assert (
        fake_client.messages.last_request["model"]
        == "claude-sonnet-5"
    )
    assert (
        fake_client.messages.last_request["max_tokens"]
        == 4096
    )
    assert (
        fake_client.messages.last_request[
            "output_format"
        ]
        is ResponseAgentOutput
    )

    user_content = (
        fake_client.messages.last_request["messages"][
            0
        ][
            "content"
        ]
    )
    assert '"expected_supply_gap_days": "7"' in user_content


def test_raises_error_when_llm_has_no_parsed_output(
    monkeypatch,
):
    fake_client = FakeAnthropicClient(None)

    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_client",
        lambda: fake_client,
    )
    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_model",
        lambda: "claude-sonnet-5",
    )
    monkeypatch.setattr(
        "app.multi_agent.llm.response_generator."
        "get_anthropic_max_tokens",
        lambda: 4096,
    )

    with pytest.raises(
        RuntimeError,
        match="LLM 구조화 출력 결과가 없습니다",
    ):
        generate_response_with_llm(
            create_state(),
        )
