import logging
import os

from app.schemas.summary import SummaryResult

logger = logging.getLogger(__name__)

# 추출 요약(2문장 이내)과 달리, 경영기획 상세 화면용으로 배경·원인·공급망 영향까지 담은
# 자세한 요약을 만든다. 불릿이 아니라 자연스러운 문단으로.
_SYSTEM_PROMPT = """당신은 배터리 제조사의 공급망 리스크 분석가입니다.
아래 뉴스 기사를 읽고 한국어로 '자세한 요약'을 작성하세요.

요구사항:
- 3~5문장으로, 한 줄 요약보다 풍부하게 핵심을 담습니다.
- 무슨 일이 일어났는지(사건), 왜 일어났는지(배경·원인), 핵심광물 공급망에 어떤 영향이
  있는지(영향·시사점)를 포함합니다.
- 기사에 없는 내용을 지어내지 말고, 과장 없이 사실 중심으로 작성합니다.
- 불릿 목록이 아니라 자연스러운 문단으로 작성합니다.
"""

# 비용/토큰 절감 — 본문 앞부분만으로도 요약이 가능하다(추출의 6000자보다 넉넉히).
_MAX_CHARS = 8000


class SummaryService:
    """뉴스 원문을 경영기획 상세용 '자세한 한국어 요약'으로 변환한다.

    OPENAI_API_KEY가 있으면 gpt-4o-mini로 실제 요약을, 없거나 실패하면 mock(원문 앞부분)을
    반환한다. mock 결과는 SummaryResult.mock=True로 표시돼, 호출자가 저장을 건너뛸 수 있다.
    """

    MODEL_VERSION_LLM = "gpt-4o-mini-summary-v1"
    MODEL_VERSION_MOCK = "summary-mock-v1"

    def __init__(self, use_openai: bool) -> None:
        self._client = None
        if use_openai:
            from openai import OpenAI

            self._client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

    def summarize(self, title: str, content: str) -> SummaryResult:
        text = (content or "").strip()
        if self._client is None:
            return self._mock(title, text)
        try:
            completion = self._client.chat.completions.create(
                model="gpt-4o-mini",
                messages=[
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": f"제목: {title}\n\n본문:\n{text[:_MAX_CHARS]}"},
                ],
                temperature=0.2,
            )
            summary = (completion.choices[0].message.content or "").strip()
            if not summary:
                return self._mock(title, text)
            return SummaryResult(summary_kr=summary, mock=False, model_version=self.MODEL_VERSION_LLM)
        except Exception as exception:  # noqa: BLE001 - 요약 실패는 화면을 막지 않고 폴백한다
            logger.warning("요약 LLM 호출 실패, mock 폴백: %s", exception)
            return self._mock(title, text)

    def _mock(self, title: str, text: str) -> SummaryResult:
        # 실제 요약이 아니라 원문 앞부분(또는 제목)일 뿐이므로 mock=True로 표시한다.
        fallback = text[:400] if text else (title or "")
        return SummaryResult(summary_kr=fallback, mock=True, model_version=self.MODEL_VERSION_MOCK)
