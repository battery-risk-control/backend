from app.multi_agent.graph.routing import (
    MAX_RETRY_COUNT,
    SupervisorRoute,
)
from app.multi_agent.graph.state import BriefingState


def select_next_route(
    state: BriefingState,
) -> SupervisorRoute:
    """현재 상태를 확인하여 다음 실행 경로를 결정한다."""

    # Reviewer 검증을 통과했다면 전체 작업을 종료한다.
    if state.get("review_passed") is True:
        return "finish"

    # Reviewer 검증 실패 시 문제가 발생한 에이전트를 재실행한다.
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

    # KG 리졸버를 가장 먼저 태워 게이트를 판정한다.
    if state.get("kg_context") is None:
        return "kg"

    # 매칭 없음 또는 재고 충분 -> erp/contract/risk/response를
    # 건너뛰고 KG 노드가 이미 채워둔 경량 결과로 바로 종료한다.
    if state.get("kg_shortage_detected") is False:
        return "finish"

    # 최초 실행 순서
    if not state.get("erp_assessment"):
        return "erp"

    if not state.get("contract_assessment"):
        return "contract"

    # Contract Agent가 ERP 확인 질문을 생성했다면
    # ERP Agent가 계약 근거를 반영하여 한 번 재검토한다.
    if (
        state.get("questions_for_erp_agent")
        and not state.get(
            "erp_reassessment_done",
            False,
        )
    ):
        return "erp_recheck"

    if state.get("procurement_risk_level") is None:
        return "risk"

    if not state.get("briefing"):
        return "response"

    return "reviewer"


def supervisor_node(
    state: BriefingState,
) -> dict:
    """결정된 다음 실행 경로를 상태에 기록한다."""

    next_route = select_next_route(state)

    return {
        "supervisor_next": next_route,
    }