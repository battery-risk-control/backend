from typing import Any

from fastapi import APIRouter, Depends

from app.api.dependencies import (
    get_multi_agent_graph,
)
from app.multi_agent.api_schemas import (
    MultiAgentBriefingRequest,
    MultiAgentBriefingResponse,
)
from app.schemas.common import (
    ApiErrorResponse,
    ApiResponse,
)


router = APIRouter(
    prefix="/api/v1/internal/multi-agent",
    tags=["multi-agent"],
)

ERROR_RESPONSES = {
    422: {
        "model": ApiErrorResponse,
    },
    500: {
        "model": ApiErrorResponse,
    },
}


@router.post(
    "/briefings",
    response_model=ApiResponse[
        MultiAgentBriefingResponse
    ],
    responses=ERROR_RESPONSES,
)
def create_multi_agent_briefing(
    request: MultiAgentBriefingRequest,
    graph: Any = Depends(
        get_multi_agent_graph,
    ),
) -> ApiResponse[MultiAgentBriefingResponse]:
    """
    Spring이 전달한 뉴스 분석 결과와 ERP 데이터를 이용해
    멀티에이전트 구매 위험 브리핑을 생성한다.
    """

    initial_state = request.model_dump(
        by_alias=False,
    )
    initial_state["retry_count"] = 0

    result = graph.invoke(
        initial_state,
    )

    response_fields = {
        field_name: result.get(field_name)
        for field_name
        in MultiAgentBriefingResponse.model_fields
    }

    response_data = (
        MultiAgentBriefingResponse.model_validate(
            response_fields,
        )
    )

    return ApiResponse(
        data=response_data,
    )