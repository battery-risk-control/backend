from app.multi_agent.graph.state import BriefingState
from app.multi_agent.llm.client import (
    get_openai_briefing_model,
)
from app.multi_agent.llm.config import (
    use_claude_for_briefing,
)
from app.multi_agent.llm.response_generator import (
    generate_response_with_llm,
)


def create_recommended_actions(
    risk_level: str,
) -> list[str]:
    """위험 단계에 따른 기본 권고 조치를 생성한다."""

    if risk_level == "critical":
        return [
            (
                "현재 재고와 미입고 발주의 납기 및 수량을 "
                "즉시 확인합니다."
            ),
            (
                "확인된 부족분을 기준으로 추가 발주 필요성을 "
                "검토합니다."
            ),
            (
                "등록된 대체 공급사와 실제 조달 가능 여부를 "
                "확인합니다."
            ),
            (
                "납기 지연 관련 계약 근거와 대응 절차를 "
                "확인합니다."
            ),
        ]

    if risk_level == "warning":
        return [
            "공급사의 납기 변경 여부를 확인합니다.",
            (
                "재고와 미입고 발주 현황을 지속적으로 "
                "모니터링합니다."
            ),
            (
                "관련 계약 근거와 대체 조달 가능성을 "
                "검토합니다."
            ),
        ]

    return [
        "현재 공급망 상태를 정기적으로 모니터링합니다.",
    ]


def build_contract_evidence_text(
    contract_findings: list[dict],
) -> str:
    """검색된 계약 근거를 훑어보기 쉬운 불릿 목록으로 만든다.

    예전엔 공백으로 이어붙인 한 문장이라(특히 고객사 여러 건일 때) 어디서 한 조항이
    끝나고 다음이 시작되는지 눈에 안 들어왔다 — 조항 하나당 한 줄로 쪼갠다.
    """

    if not contract_findings:
        return (
            "- 현재 검색된 계약 근거에서 관련 조항을 "
            "확인하지 못했습니다. 계약서 추가 확인이 필요합니다."
        )

    evidence_lines = []

    for finding in contract_findings:
        contract_id = finding.get(
            "contract_id",
            "unknown",
        )
        page = finding.get(
            "page",
            finding.get("page_number", "unknown"),
        )
        clause_name = finding.get(
            "clause_name_kr",
            finding.get("clause_type", "관련 조항"),
        )
        evidence_text = finding.get(
            "evidence_text",
            finding.get("content", "근거 내용 없음"),
        )

        evidence_lines.append(
            f"- (계약ID: {contract_id}, p.{page}) "
            f"{clause_name}: {evidence_text}"
        )

    return "\n".join(evidence_lines)


def generate_briefing_node(
    state: BriefingState,
) -> dict:
    """
    LLM 브리핑을 생성하고 실패하면 규칙 기반 브리핑으로 대체한다.
    """

    llm_error = None

    if state.get("use_llm", False):
        try:
            llm_result = generate_response_with_llm(
                state,
            )

            result = {
                "recommended_actions": (
                    llm_result["recommended_actions"]
                ),
                "briefing": llm_result["briefing"],
                "llm_used": True,
                "llm_error": None,
            }

            # 운영 경로(Claude)가 아니면 어느 모델이 썼는지 결과에 남긴다. llm_used만으로는
            # "LLM이 썼다"까지만 알 수 있어, 나중에 이 브리핑을 보고 Claude가 쓴 문장으로
            # 오해할 수 있다. KG 게이트 우회 표식과 같은 이유다.
            if not use_claude_for_briefing():
                result["warnings"] = [
                    *state.get("warnings", []),
                    (
                        "브리핑을 OpenAI로 생성했습니다"
                        f"(BRIEFING_USE_CLAUDE=false, {get_openai_briefing_model()}). "
                        "운영 경로인 Claude와 문장 품질이 다를 수 있습니다."
                    ),
                ]

            return result

        except Exception as error:
            # API 장애, timeout 또는 출력 검증 실패 시에도
            # 전체 멀티에이전트 흐름을 중단하지 않는다.
            llm_error = (
                f"{type(error).__name__}: "
                "LLM 브리핑 생성에 실패했습니다."
            )

    news_id = state.get(
        "news_id",
        "unknown",
    )
    title = state.get(
        "title",
        "제목 없음",
    )
    impact_domain = state.get(
        "impact_domain_final",
        "unknown",
    )
    risk_level = state.get(
        "procurement_risk_level",
        state.get("severity_tier", "normal"),
    )
    risk_score = state.get(
        "procurement_risk_score",
        state.get("severity_score", 0),
    )

    affected_materials = state.get(
        "affected_materials",
        [],
    )
    material_text = (
        ", ".join(affected_materials)
        if affected_materials
        else "확인되지 않음"
    )

    erp_assessment = state.get(
        "erp_assessment",
        {},
    )
    erp_findings = erp_assessment.get(
        "findings",
        [],
    )
    erp_text = (
        "\n".join(f"- {finding}" for finding in erp_findings)
        if erp_findings
        else "- ERP 분석 근거가 없습니다."
    )

    contract_text = build_contract_evidence_text(
        state.get("contract_findings", []),
    )

    outbound_contract_findings = state.get(
        "outbound_contract_findings",
        [],
    )
    outbound_detailed_count = len(
        state.get("outbound_contracts", []) or [],
    )
    outbound_total_matched = state.get(
        "outbound_contracts_total_matched",
        0,
    )
    outbound_remainder_note = (
        (
            f" (이 외 총 {outbound_total_matched}건의 계약이 "
            "동일 원자재 공급에 영향받으며, 재무 노출도 상위 "
            f"{outbound_detailed_count}건만 상세 표기)"
        )
        if outbound_total_matched > outbound_detailed_count
        else ""
    )
    outbound_section = (
        (
            "\n\n[완성차 고객사 납품 지연 시 배상책임]\n"
            "(원자재 공급 차질이 지속될 경우 참고, "
            "완제품 재고 데이터가 없어 실제 확정 여부는 확인 불가)\n"
            + build_contract_evidence_text(
                outbound_contract_findings,
            )
            + outbound_remainder_note
        )
        if outbound_contract_findings
        else ""
    )

    kg_evidence_paths = state.get(
        "kg_evidence_paths",
        [],
    )
    kg_text = (
        "\n".join(f"- {path}" for path in kg_evidence_paths)
        if kg_evidence_paths
        else "- KG 근거 경로가 없습니다."
    )

    risk_reasons = state.get(
        "risk_reasons",
        [],
    )
    risk_reason_text = (
        "\n".join(f"- {reason}" for reason in risk_reasons)
        if risk_reasons
        else "- 상세 위험도 결정 근거가 없습니다."
    )
    tldr_fact = (
        risk_reasons[0] if risk_reasons else "ERP·계약 근거 확인 필요"
    )

    recommended_actions = create_recommended_actions(
        risk_level,
    )
    action_text = "\n".join(
        f"- {action}"
        for action in recommended_actions
    )

    briefing = (
        f"[요약] {risk_level} {risk_score}점 — {tldr_fact}\n\n"
        f"[사건] {title} (뉴스 ID: {news_id})\n"
        f"- 영향 원자재: {material_text}\n"
        f"- 영향 영역: {impact_domain}\n\n"
        f"[최종 위험 단계와 점수]\n"
        f"- 등급: {risk_level}\n"
        f"- 점수: {risk_score}\n"
        f"{risk_reason_text}\n\n"
        f"[KG 근거]\n{kg_text}\n\n"
        f"[ERP 분석]\n{erp_text}\n\n"
        f"[계약 근거]\n{contract_text}"
        f"{outbound_section}\n\n"
        f"[권고 조치]\n{action_text}"
    )

    return {
        "recommended_actions": recommended_actions,
        "briefing": briefing,
        "llm_used": False,
        "llm_error": llm_error,
    }