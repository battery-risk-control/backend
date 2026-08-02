from app.multi_agent.graph.routing import (
    MAX_NEGOTIATION_ROUNDS,
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

    negotiation_round = state.get(
        "negotiation_round",
        0,
    )

    # Contract Agent가 ERP 확인 질문을 남겼고 라운드 한도 안이면
    # ERP Agent가 계약 근거를 반영하여 재검토한다.
    # (recheck_soojung_erp_node가 처리 후 questions_for_erp_agent를 비우므로
    # 같은 질문으로 두 번 재진입하지 않는다.)
    if (
        state.get("questions_for_erp_agent")
        and negotiation_round < MAX_NEGOTIATION_ROUNDS
    ):
        return "erp_recheck"

    # ERP 재검토가 "아직 근거가 부족하다"며 Contract Agent에게 한 번 더
    # 확인을 요청했고 라운드 한도 안이면 — 진짜 왕복 협상.
    if (
        state.get("questions_for_contract_agent_round2")
        and negotiation_round < MAX_NEGOTIATION_ROUNDS
    ):
        return "contract"

    if state.get("procurement_risk_level") is None:
        return "risk"

    # 재고부족이 확정된 원자재(stockout_before_eta)와 연결된 아웃바운드 계약이 있으면
    # 완성차 고객사 납품 지연 시 배상책임을 한 번만 조회한다(왕복 없는 단발성 조회).
    # 완제품 재고 데이터가 없어 "실제로 늦어질지"는 확정 못 하므로, 원자재가 끊길
    # 가능성이 있는 경우로 게이트를 좁혀서 불필요한 조회를 줄인다.
    erp_assessment = state.get("erp_assessment", {})
    if (
        not state.get("outbound_contract_checked")
        and erp_assessment.get("stockout_before_eta") is True
        and state.get("outbound_contract_id") is not None
    ):
        return "outbound_contract"

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