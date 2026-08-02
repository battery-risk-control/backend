import json

from app.multi_agent.graph.state import BriefingState
from app.multi_agent.llm.client import (
    get_anthropic_client,
    get_anthropic_max_tokens,
    get_anthropic_model,
    get_openai_briefing_client,
    get_openai_briefing_model,
)
from app.multi_agent.llm.config import (
    use_claude_for_briefing,
)
from app.multi_agent.llm.schemas import (
    ResponseAgentOutput,
)


SYSTEM_PROMPT = """
당신은 배터리 원자재 공급망의 구매 위험 브리핑을
작성하는 Response Agent입니다.

위험 점수와 위험 단계는 Python 규칙 엔진이 이미 결정했습니다.
이를 변경하거나 다시 계산하지 마세요.

다음 규칙을 반드시 지키세요.

1. 입력으로 제공된 사실만 사용하세요.
2. 입력에 없는 수량, 날짜, 공급사, 계약 조항을 만들지 마세요.
3. 계약 근거를 언급할 때 contract_id와 page를 함께 표시하세요.
4. 검색 결과가 없으면 계약 조항이 존재하지 않는다고 단정하지 마세요.
5. 확인되지 않은 내용은 '추가 확인 필요'라고 표현하세요.
6. 질문 목록은 사실 근거가 아니라 확인해야 할 항목입니다.
7. stockout_before_eta가 true라면 입고 전에 재고가
   소진될 가능성이 있다고 표현하세요.
8. has_alternative_supplier가 false라면 등록된 대체 공급사가
   없다고 표현하세요.
9. 미입고 발주가 존재하면 즉시 추가 발주를 지시하지 말고,
   기존 발주의 납기와 수량을 먼저 확인하도록 권고하세요.
10. 계약 변경은 즉시 가능하다고 표현하지 말고,
    차기 갱신 또는 재협상 시 검토하도록 권고하세요.
11. 모든 내용은 한국어로 간결하고 구체적으로 작성하세요.
12. 확정되지 않은 미래 사건을 단정적으로 표현하지 마세요.
13. expected_supply_gap_days는 재고 부족이 시작되는 시점이 아니라,
    현재 재고 소진부터 다음 입고까지 이어지는 예상 공급 공백 기간입니다.
    예를 들어 값이 1.5이면 '1.5일 후 부족'이 아니라
    '약 1.5일의 공급 공백이 예상된다'고 표현하세요.
14. stockout_before_eta가 true라면 erp_assessment.supplier_assessments와
    kg_evidence.alternative_suppliers를 확인해서 구체적인 대체 공급사
    후보를 언급하고, 승인상태·리드타임 등 왜 그 후보가 적합한지 근거를
    함께 제시하세요. 후보가 없거나 비어 있으면 등록된 대체 공급사가
    없다고 명시하세요.
15. contract_findings 중 clause_type이 force_majeure, delivery_delay,
    volume_commitment, termination인 항목이 있으면 그 원문
    (evidence_text)을 근거로 공급사에게 요구할 수 있는 조치를
    제시하세요.
16. 규칙 15에 따라 "이걸 요구할 수 있다"고 쓸 때는 반드시 evidence_text
    원문에서 그 의무를 지는 주체가 공급사인지 LG엔솔인지 실제로
    확인하세요. clause_type이라는 이름(예: delivery_delay)은 조항의
    주제일 뿐 의무 방향을 보장하지 않습니다. 원문에 의무 주체가
    명확히 나와 있지 않으면 "어느 쪽 의무인지 계약 원문 추가 확인
    필요"라고 표현하고 LG엔솔에 유리한 방향으로 단정하지 마세요.
17. outbound_contract_findings가 있으면, 원자재 공급 차질이 지속될 경우
    발생할 수 있는 완성차 고객사 납품 지연의 배상책임(지체상금 등)을
    그 원문(evidence_text) 근거와 함께 브리핑에 포함하세요.
    finished_goods_inventory.available이 false이므로 완제품(배터리)
    버퍼 재고 부족이 확정됐다고 단정하지 말고, "원자재 공급 차질이
    지속될 경우 참고할 배상책임"이라는 조건부로 표현하세요.
    outbound_contract_findings가 비어 있으면 이 항목은 생략하세요.
    findings에 서로 다른 contract_id(고객사)가 여러 건 섞여 있으면
    고객사별로 구분해서 서술하세요. outbound_contracts_summary의
    total_matched가 detailed_count보다 크면, 상세 검색 대상에 포함되지
    않은 나머지 계약은 "이 외 N건의 계약도 동일 원자재 공급에 영향받음"
    처럼 개수만 언급하고 근거 없이 내용을 추정하지 마세요.

브리핑은 구매팀이 훑어보기 쉬워야 합니다. 아래 뼈대를 그대로 따라 작성하세요 — 산문 한 단락에
여러 사실을 이어붙이지 말고, 각 항목을 짧은 불릿으로 쪼개세요. 대괄호 안 설명은 지시일 뿐이니
그대로 출력하지 말고 실제 내용으로 채우세요.

```
[요약] {위험 단계} {점수}점 — {가장 중요한 사실 한 문장}

[최종 위험 단계와 점수]
- 등급: {critical/warning/normal}
- 점수 구성: 외부신호 {X}점 · ERP노출 {Y}점 · 계약공백 {Z}점

[ERP 노출 근거]
- 재고: 현재 {N}일치 (안전재고 {M}일 대비 부족/충분)
- 다음 입고: {N}일 후, 공급 공백 약 {N}일 예상
- 공급사 상태: {요약}

[계약서에서 실제 확인된 근거]
- (계약ID: {id}, p.{page}) {조항명}: {핵심 내용 한 줄}
(여러 조항이면 한 줄씩 추가)

[대체 공급사 추천]
- {후보명}({국가}·{위험도}·리드타임 {N}일): {적합성 근거}
(후보가 없으면 "등록된 대체 공급사가 없습니다" 한 줄)

[공급사에게 요구 가능한 조치]
- {조치 1}
- {조치 2}

[완성차 고객사 납품 지연 시 배상책임] (outbound_contract_findings가 있을 때만, 없으면 이 섹션 전체 생략)
원자재 공급 차질이 지속될 경우 참고할 사항이라는 문구를 표 앞에 한 줄 넣고, 그 아래 마크다운
표로 정리하세요. findings에 서로 다른 contract_id(고객사)가 있으면 고객사별로 한 행씩 씁니다.
| 고객사 | 계약ID | 지연 위약금 | Line-Stop |
|---|---|---|---|
| {고객사명} | {contract_id} | {예: 주당 1.0%} | {예: USD 500,000} |
(outbound_contracts_summary.total_matched가 detailed_count보다 크면 표 아래에
"이 외 {차이}건의 계약도 동일 원자재 공급에 영향받습니다(재무 노출도 상위 {detailed_count}건만 상세 표기)."
한 줄 추가. 내용을 추정하지 말고 개수만 언급하세요.)

[아직 확인되지 않은 사항]
- {확인 필요 항목 1}
- {확인 필요 항목 2}

[구매팀 권고 조치]
- {recommended_actions와 일치하는 조치들을 불릿으로}
```
""".strip()


def build_response_payload(
    state: BriefingState,
) -> dict:
    """
    LLM에 필요한 분석 결과만 전달한다.

    뉴스 원문 전체나 ERP 원본 전체가 아니라,
    각 Agent가 구조화한 근거를 사용한다.
    """

    return {
        "metric_semantics": {
        "expected_supply_gap_days": (
        "현재 재고 소진 시점부터 다음 입고 시점까지의 "
        "예상 공급 공백 기간(일)입니다. "
        "재고 부족이 시작되기까지 남은 시간이 아닙니다."
            ),
        },
        "news": {
            "news_id": state.get("news_id"),
            "title": state.get("title"),
            "summary_kr": state.get("summary_kr"),
            "impact_domain_final": state.get(
                "impact_domain_final",
            ),
            "affected_materials": state.get(
                "affected_materials",
                [],
            ),
        },
        "procurement_risk": {
            "level": state.get(
                "procurement_risk_level",
            ),
            "score": state.get(
                "procurement_risk_score",
            ),
            "reasons": state.get(
                "risk_reasons",
                [],
            ),
        },
        "erp_assessment": state.get(
            "erp_assessment",
            {},
        ),
        "erp_reassessment": state.get(
            "erp_reassessment",
            {},
        ),
        "contract_assessment": state.get(
            "contract_assessment",
            {},
        ),
        "contract_findings": state.get(
            "contract_findings",
            [],
        ),
        "outbound_contract_findings": state.get(
            "outbound_contract_findings",
            [],
        ),
        "outbound_contracts_summary": {
            "detailed_count": len(
                state.get("outbound_contracts", []) or [],
            ),
            "total_matched": state.get(
                "outbound_contracts_total_matched",
                0,
            ),
        },
        "finished_goods_inventory": {
            "available": False,
            "note": (
                "완제품(배터리) 재고 데이터는 ERP에 없어 "
                "실제 버퍼 여력은 확인할 수 없습니다."
            ),
        },
        "kg_evidence": {
            "evidence_paths": state.get(
                "kg_evidence_paths",
                [],
            ),
            "affected_suppliers": state.get(
                "kg_affected_suppliers",
                [],
            ),
            "alternative_suppliers": state.get(
                "kg_alternative_suppliers",
                [],
            ),
        },
    }


def _generate_with_claude(payload_json: str) -> ResponseAgentOutput | None:
    """Claude(Anthropic) 구조화 출력. 운영 경로."""

    response = get_anthropic_client().messages.parse(
        model=get_anthropic_model(),
        max_tokens=get_anthropic_max_tokens(),
        system=SYSTEM_PROMPT,
        messages=[
            {
                "role": "user",
                "content": payload_json,
            },
        ],
        output_format=ResponseAgentOutput,
    )

    return response.parsed_output


def _generate_with_openai(payload_json: str) -> ResponseAgentOutput | None:
    """
    OpenAI 구조화 출력. `BRIEFING_USE_CLAUDE=false`일 때만 쓰인다.

    같은 `SYSTEM_PROMPT`·같은 페이로드·같은 출력 스키마를 쓴다 — 다른 것은 호출 SDK뿐이라
    두 경로의 결과를 나란히 비교할 수 있다. Anthropic이 `system`을 별도 인자로 받는 것과
    달리 OpenAI는 첫 메시지의 role로 받으므로 그 부분만 모양이 다르다.

    `max_tokens`를 넘기지 않는다. 그 값은 Claude의 extended thinking 예산까지 함께
    계산해 8192로 잡은 것이라(2026-07-31 실측) thinking이 없는 모델에 그대로 적용할 근거가
    없다. 모델 기본 상한에 맡긴다.
    """

    response = get_openai_briefing_client().chat.completions.parse(
        model=get_openai_briefing_model(),
        messages=[
            {
                "role": "system",
                "content": SYSTEM_PROMPT,
            },
            {
                "role": "user",
                "content": payload_json,
            },
        ],
        response_format=ResponseAgentOutput,
    )

    return response.choices[0].message.parsed


def generate_response_with_llm(
    state: BriefingState,
) -> dict:
    """
    구조화된 출력으로 구매팀 브리핑과 권고 조치를 생성한다.

    provider는 `BRIEFING_USE_CLAUDE`가 정한다(기본 Claude). 어느 쪽이든 실패하면 예외를
    올리고, `briefing_node`가 그것을 잡아 규칙 기반 브리핑으로 폴백한다.
    """

    payload_json = json.dumps(
        build_response_payload(state),
        ensure_ascii=False,
        default=str,
    )

    parsed = (
        _generate_with_claude(payload_json)
        if use_claude_for_briefing()
        else _generate_with_openai(payload_json)
    )

    if parsed is None:
        raise RuntimeError(
            "LLM 구조화 출력 결과가 없습니다."
        )

    result = parsed.model_dump()

    briefing = result.get(
        "briefing",
        "",
    ).strip()

    risk_level = state.get(
        "procurement_risk_level",
    )

    metadata_lines = [
        "[validation metadata]",
    ]

    if risk_level:
        metadata_lines.append(
            f"final_risk_level: {risk_level}"
        )

    seen_citations = set()

    for finding in state.get(
        "contract_findings",
        [],
    ):
        contract_id = finding.get("contract_id")
        page = finding.get(
            "page",
            finding.get("page_number"),
        )

        if contract_id in (None, ""):
            continue

        if page is None:
            continue

        citation = (
            f"[contract_id: {contract_id}, "
            f"page: {page}]"
        )

        if citation in seen_citations:
            continue

        seen_citations.add(citation)
        metadata_lines.append(citation)

    result["briefing"] = (
        briefing
        + "\n\n"
        + "\n".join(metadata_lines)
    )

    return result