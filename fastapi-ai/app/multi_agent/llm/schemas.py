from pydantic import BaseModel, ConfigDict, Field


class ResponseAgentOutput(BaseModel):
    """
    Response Agent가 생성해야 하는 구조화된 출력.

    LLM은 위험 점수와 위험 단계를 변경할 수 없으며,
    브리핑 문장과 구매팀 권고 조치만 생성한다.
    """

    model_config = ConfigDict(
        extra="forbid",
    )

    recommended_actions: list[str] = Field(
        min_length=1,
        max_length=5,
        description=(
            "구매팀이 실행할 수 있는 "
            "구체적인 권고 조치 목록"
        ),
    )

    briefing: str = Field(
        min_length=1,
        description=(
            "ERP와 계약서 근거를 기반으로 생성한 "
            "구매팀용 한국어 브리핑"
        ),
    )