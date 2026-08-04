"""
영문 뉴스 제목 → 한국어 번역. (공개 대시보드 뉴스 마퀴·목록용, 2026-07-31)

GDELT는 영문 기사만 수집하는데(sourcelang:english) 화면은 한국어라 원문 제목을 그대로 띄우면
비로그인 방문자가 읽을 수 없다. Spring의 번역 스케줄러가 아직 번역되지 않은 제목을 모아 보내면
여기서 한 번의 LLM 호출로 한꺼번에 번역해 돌려준다.

**제목만** 번역한다. 본문까지 번역하면 기사당 토큰이 수십 배로 뛰는데, 마퀴와 목록에 필요한 건
제목뿐이다. 본문 요약이 필요하면 이미 추출 경로에 summary_kr이 있다.

**한 번의 호출에 여러 건을 묶는다.** 제목 하나에 호출 하나씩 쓰면 15분마다 수십 번을 부르게 되고,
제목은 짧아서 묶어도 컨텍스트에 여유가 있다.

OPENAI_API_KEY가 없으면 번역하지 않고 빈 목록을 돌려준다 — Spring은 미번역으로 두고 화면은
원문을 그대로 보여주므로, 키가 없어도 파이프라인 전체가 멈추지는 않는다.
"""
import logging
import os

from pydantic import BaseModel, Field

from app.schemas.translation import TitleToTranslate, TranslatedTitle, TranslateTitlesResult

log = logging.getLogger(__name__)

MODEL = "gpt-4o-mini"
MODEL_VERSION = "gpt-4o-mini-title-ko-v1"

# 한 번의 호출에 묶는 최대 건수. 제목은 짧지만 너무 많이 묶으면 LLM이 뒤쪽을 빠뜨리기 시작한다.
MAX_TITLES_PER_CALL = 30

_SYSTEM_PROMPT = """
당신은 원자재·공급망 뉴스를 다루는 번역가입니다. 영문 뉴스 제목을 한국어로 번역하세요.

규칙:
- 뉴스 헤드라인답게 간결하게. 설명을 덧붙이거나 문장을 늘리지 마세요.
- 회사명·지명·광산명 같은 고유명사는 널리 쓰이는 한국어 표기가 있으면 그걸 쓰고,
  없으면 원문을 그대로 두세요. 억지로 음차하지 마세요.
- 원자재명은 한국어로: lithium=리튬, cobalt=코발트, nickel=니켈, graphite=흑연,
  manganese=망간, copper=구리, aluminum=알루미늄, rare earth=희토류.
- 원문에 없는 내용을 추가하지 마세요. 추측해서 채우지 마세요.
- 입력으로 받은 id를 그대로 돌려주세요. id를 바꾸거나 새로 만들지 마세요.
- 모든 항목을 빠짐없이 번역하세요.
""".strip()


class _TranslatedTitleOut(BaseModel):
    id: str = Field(description="입력으로 받은 id를 그대로")
    title_ko: str = Field(description="한국어로 번역된 제목")


class _TranslatedTitlesOut(BaseModel):
    items: list[_TranslatedTitleOut]


def translate_titles(items: list[TitleToTranslate]) -> TranslateTitlesResult:
    if not items:
        return TranslateTitlesResult(items=[], model_version=MODEL_VERSION, mock=False)

    if not os.getenv("OPENAI_API_KEY", "").strip():
        log.warning("OPENAI_API_KEY가 없어 제목 번역을 건너뜁니다(화면은 원문 표시).")
        return TranslateTitlesResult(items=[], model_version=MODEL_VERSION, mock=True)

    batch = items[:MAX_TITLES_PER_CALL]
    try:
        from openai import OpenAI

        client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
        payload = "\n".join(f"{item.id}\t{item.title}" for item in batch)
        completion = client.beta.chat.completions.parse(
            model=MODEL,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": f"아래는 'id<TAB>제목' 형식입니다.\n\n{payload}"},
            ],
            response_format=_TranslatedTitlesOut,
            temperature=0.1,
        )
        parsed = completion.choices[0].message.parsed
    except Exception as exception:  # 번역 실패가 수집 파이프라인을 멈추게 하지 않는다.
        log.warning("제목 번역 호출 실패: %s", exception)
        return TranslateTitlesResult(items=[], model_version=MODEL_VERSION, mock=False)

    # LLM이 없는 id를 지어내거나 빈 번역을 돌려줄 수 있어, 요청에 있던 id만 통과시킨다.
    requested = {item.id for item in batch}
    translated = [
        TranslatedTitle(id=out.id, title_ko=out.title_ko.strip())
        for out in (parsed.items if parsed else [])
        if out.id in requested and out.title_ko and out.title_ko.strip()
    ]
    log.info("제목 번역 완료: 요청 %d건 → 반환 %d건", len(batch), len(translated))
    return TranslateTitlesResult(items=translated, model_version=MODEL_VERSION, mock=False)
