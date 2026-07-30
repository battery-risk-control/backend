from operator import add
from typing import Annotated, Literal, TypedDict

from app.schemas.contract import (
    ContractAgentRequest,
)

from app.schemas.erp import (
    ErpExposureRequest,
    ErpExposureResponse,
)


ErpAgentStatus = Literal[
    "PENDING",
    "COMPLETED",
    "NOT_REQUIRED",
    "MANUAL_REVIEW",
    "FAILED",
]


ErpNextNode = Literal[
    "contractAgent",
    "finalRiskCalculator",
    "manualReview",
    "end",
]


class ProcurementRiskState(TypedDict, total=False):
    """
    멀티 에이전트가 공동으로 읽고 수정하는 상태.

    total=False이므로 모든 필드를 처음부터 넣을 필요는 없다.
    각 Agent는 자신이 담당하는 필드만 반환할 수 있다.
    """

    # 사건 식별 정보
    eventId: str
    newsIds: list[str]

    # Spring에서 전달받은 ERP 원본 데이터
    erpRequest: ErpExposureRequest

    # ERP Exposure Agent 계산 결과
    erpExposure: ErpExposureResponse | None

    # Contract Agent에 전달할 요청
    contractRequest: ContractAgentRequest | None

    # ERP Agent 실행 상태
    erpAgentStatus: ErpAgentStatus

    # ERP Agent 실행 후 이동할 노드
    erpNextNode: ErpNextNode

    # 여러 Agent가 반환한 오류를 누적
    errors: Annotated[list[str], add]


def createErpAgentState(
    request: ErpExposureRequest,
    newsIds: list[str] | None = None,
) -> ProcurementRiskState:
    """
    ERP Exposure Agent 실행 전 초기 상태를 생성한다.
    """

    return {
        "eventId": request.eventId,
        "newsIds": list(newsIds or []),
        "erpRequest": request,
        "erpExposure": None,
        "contractRequest": None,
        "erpAgentStatus": "PENDING",
        "errors": [],
    }