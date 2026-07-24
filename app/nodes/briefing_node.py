from app.graph.state import BriefingState
from app.llm.response_generator import (
    generate_response_with_llm,
)


def create_recommended_actions(
    risk_level: str,
) -> list[str]:
    """최종 구매 리스크에 따라 기본 대응방안을 생성한다."""

    if risk_level == "critical":
        return [
            "현재 재고와 일별 소진 예상량을 즉시 재확인합니다.",
            "공급사에 납기 및 생산 일정을 긴급 확인합니다.",
            "대체 공급사와 긴급 조달 가능성을 검토합니다.",
            "계약서의 납기 지연 및 불가항력 조항을 확인합니다.",
        ]

    if risk_level == "warning":
        return [
            "공급사의 납기 변경 여부를 확인합니다.",
            "재고와 미입고 발주 현황을 지속적으로 모니터링합니다.",
            "관련 계약 조항과 대체 조달 가능성을 검토합니다.",
        ]

    return [
        "현재 공급망 상태를 정기적으로 모니터링합니다.",
    ]


def generate_briefing_node(
    state: BriefingState,
) -> dict:
    """
    뉴스, ERP 분석, 계약 근거를 결합하여 구매팀 브리핑을 생성한다.

    현재는 문자열 조합 방식의 Mock Response Agent이다.
    추후 이 함수 내부의 문장 생성 부분을 LLM 호출로 교체한다.
    """
    llm_error = None

    if state.get("use_llm", False):
        try:
            llm_result = generate_response_with_llm(
                state
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
            # API 장애, 잘못된 키, timeout, 출력 검증 실패 시
            # 기존 규칙 기반 브리핑으로 계속 진행한다.
            llm_error = (
                f"{type(error).__name__}: "
                "LLM 브리핑 생성에 실패했습니다."
            )

    news_id = state.get("news_id", "unknown")
    title = state.get("title", "제목 없음")

    impact_domain = state.get(
        "impact_domain_final",
        "unknown",
    )

    # 새 최종 위험도를 우선 사용하고 기존 심각도는 호환용으로 사용
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

    # 새 ERP Agent 결과를 우선 사용
    erp_assessment = state.get("erp_assessment")

    if erp_assessment:
        erp_findings = erp_assessment.get(
            "findings",
            [],
        )
    else:
        # 기존 테스트 및 코드와의 임시 호환
        erp_findings = state.get(
            "erp_findings",
            {},
        ).get("findings", [])

    contract_findings = state.get(
        "contract_findings",
        [],
    )

    risk_reasons = state.get(
        "risk_reasons",
        [],
    )

    material_text = (
        ", ".join(affected_materials)
        if affected_materials
        else "확인되지 않음"
    )

    erp_text = (
        " ".join(erp_findings)
        if erp_findings
        else "ERP 분석 결과가 없습니다."
    )

    if contract_findings:
        contract_text = " ".join(
            (
                f'[{finding["contract_id"]} '
                f'p.{finding["page"]}] '
                f'{finding["clause_name_kr"]}: '
                f'{finding["evidence_text"]}'
            )
            for finding in contract_findings
        )
    else:
        contract_text = (
            "관련 계약 조항을 찾지 못했습니다."
        )

    risk_reason_text = (
        " ".join(risk_reasons)
        if risk_reasons
        else "상세 위험도 산정 근거가 없습니다."
    )

    recommended_actions = create_recommended_actions(
        risk_level
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
        f"[심각도] {risk_level}\n"
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
