from app.graph.routing import MAX_RETRY_COUNT, SupervisorRoute
from app.graph.state import BriefingState


def select_next_route(state: BriefingState) -> SupervisorRoute:
    """현재 State를 확인하여 다음 실행 경로를 결정한다."""

    # Reviewer 검증을 통과하면 종료
    if state.get("review_passed") is True:
        return "finish"

    # Reviewer 검증에 실패하면 해당 담당 영역 재실행
    if state.get("review_passed") is False:
        retry_count = state.get("retry_count", 0)
        error_owner = state.get("error_owner")

        if retry_count >= MAX_RETRY_COUNT:
            return "finish"

        if error_owner == "erp":
            return "erp"

        if error_owner == "contract":
            return "contract"

        if error_owner == "response":
            return "response"

    # 최초 실행 순서
    if not state.get("erp_assessment"):
        return "erp"

    if not state.get("contract_assessment"):
        return "contract"

        # Contract Agent가 ERP 확인 질문을 생성했고
    # 아직 ERP 재평가를 하지 않았다면 같은 ERP Agent를 재호출
    if (
        state.get("questions_for_erp_agent")
        and not state.get("erp_reassessment_done", False)
    ):
        return "erp_recheck"

    if state.get("procurement_risk_level") is None:
        return "risk"

    if not state.get("briefing"):
        return "response"

    return "reviewer"

def supervisor_node(state: BriefingState) -> dict:
    """LangGraph State에 Supervisor의 경로 결정 결과를 기록한다."""

    next_route = select_next_route(state)

    return {
        "supervisor_next": next_route,
    }
