from app.schemas.common import ApiModel


class TitleToTranslate(ApiModel):
    """번역 대상 1건. id는 Spring의 raw_events.id를 문자열로 담아 보내며, 번역 결과를 어느 행에
    되돌려 쓸지 짝짓는 데만 쓴다 — FastAPI는 DB에 접근하지 않는다."""

    id: str
    title: str


class TranslateTitlesRequest(ApiModel):
    items: list[TitleToTranslate]


class TranslatedTitle(ApiModel):
    id: str
    title_ko: str


class TranslateTitlesResult(ApiModel):
    """요청한 것보다 적게 돌아올 수 있다 — LLM이 일부를 빠뜨리거나 키가 없어 통째로 건너뛴 경우다.
    Spring은 돌아온 것만 반영하고 나머지는 다음 주기에 다시 보낸다."""

    items: list[TranslatedTitle]
    model_version: str
    mock: bool
