from pydantic import BaseModel, Field


class ResponseAgentOutput(BaseModel):
    """
    Response Agent가 생성해야 하는 구조화된 출력.

    LLM이 위험 점수나 위험 단계를 변경하지 못하도록
    브리핑 문장과 권고 조치만 출력하게 제한한다.
    """

    recommended_actions: list[str] = Field(
        min_length=1,
        max_length=5,
        description=(
            "구매팀이 실행할 수 있는 구체적인 권고 조치 목록"
        ),
    )

    briefing: str = Field(
        min_length=1,
        description=(
            "ERP와 계약서 근거에 기반한 구매팀용 한국어 브리핑"
        ),
    )
