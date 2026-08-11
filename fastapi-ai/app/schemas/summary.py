from app.schemas.common import ApiModel


class SummaryRequest(ApiModel):
    """경영기획 AI 브리핑 상세 전용 '자세한 뉴스 요약' 생성 요청.

    추출 단계의 summary_kr(2문장 이내)보다 풍부한 요약을 만들기 위한 별도 경로다.
    """

    title: str
    content: str


class SummaryResult(ApiModel):
    summary_kr: str
    # mock=True면 실제 LLM 요약이 아니라 폴백(원문 앞부분)이다. 호출자(Spring)는 이 값이 True면
    # 저장하지 않고 다음 조회에서 다시 시도한다.
    mock: bool = True
    model_version: str = "summary-mock-v1"
