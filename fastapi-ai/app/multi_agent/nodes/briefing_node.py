from app.multi_agent.graph.state import BriefingState
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
    """검색된 계약 근거를 사람이 읽을 수 있는 문장으로 만든다."""

    if not contract_findings:
        return (
            "현재 검색된 계약 근거에서 관련 조항을 "
            "확인하지 못했습니다. 계약서 추가 확인이 필요합니다."
        )

    evidence_items = []

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

        evidence_items.append(
            f"[계약 ID: {contract_id}, 페이지: {page}] "
            f"{clause_name}: {evidence_text}"
        )

    return " ".join(evidence_items)


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

            return {
                "recommended_actions": (
                    llm_result["recommended_actions"]
                ),
                "briefing": llm_result["briefing"],
                "llm_used": True,
                "llm_error": None,
            }

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
        " ".join(erp_findings)
        if erp_findings
        else "ERP 분석 근거가 없습니다."
    )

    contract_text = build_contract_evidence_text(
        state.get("contract_findings", []),
    )

    risk_reasons = state.get(
        "risk_reasons",
        [],
    )
    risk_reason_text = (
        " ".join(risk_reasons)
        if risk_reasons
        else "상세 위험도 결정 근거가 없습니다."
    )

    recommended_actions = create_recommended_actions(
        risk_level,
    )
    action_text = "\n".join(
        f"- {action}"
        for action in recommended_actions
    )

    briefing = (
        f"[뉴스 ID] {news_id}\n"
        f"[제목] {title}\n"
        f"[영향 원자재] {material_text}\n"
        f"[영향 영역] {impact_domain}\n"
        f"[위험 단계] {risk_level}\n"
        f"[위험 점수] {risk_score}\n"
        f"[위험도 근거] {risk_reason_text}\n"
        f"[ERP 분석] {erp_text}\n"
        f"[계약 근거] {contract_text}\n"
        f"[권고 조치]\n{action_text}"
    )

    return {
        "recommended_actions": recommended_actions,
        "briefing": briefing,
        "llm_used": False,
        "llm_error": llm_error,
    }