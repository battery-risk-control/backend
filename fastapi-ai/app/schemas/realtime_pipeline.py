from app.schemas.common import ApiModel


class RealtimeFetchRequest(ApiModel):
    cursor_value: str | None = None


class RealtimeCandidate(ApiModel):
    global_event_id: str
    title: str
    # 데모 매니페스트에 미리 번역해 둔 한국어 제목. 이 필드가 없으면 Pydantic이 값을 버려
    # Spring이 title_ko를 못 채운다(데모는 collected_at이 과거라 번역 스케줄러가 못 잡음).
    title_kr: str | None = None
    content: str
    source_url: str
    action_geo_country_code: str | None = None
    # GDELT가 이 이벤트에 이미 계산해준 값을 그대로 전달 — Spring이 나중에 국가 단위로
    # 재조회하지 않도록 한다(재조회는 같은 URL에 이벤트가 여러 개면 값이 갈리고,
    # URL 매칭 실패 시 부정확한 기본값/국가평균으로 샌다).
    goldstein_scale: float | None = None
    num_articles: int | None = None
    avg_tone: float | None = None
    original_event_date: str | None = None
    # 데모 시간압축: 이 이벤트를 넣을 사이트 날짜 버킷(0=오늘,1=어제,2=그제). Spring이
    # collected_at을 "실뉴스 상단 앵커 − demo_day일"로 배치해 3일치 타임라인을 만든다.
    demo_day: int | None = None
    # (C) 추출 오버라이드용 — Spring이 LLM 재추출 대신 이 값으로 자재·관련성을 강제한다.
    material_enum: str | None = None
    event_type: str | None = None
    tone_score: float | None = None
    impact_domain: str | None = None


class RealtimeFetchResult(ApiModel):
    items: list[RealtimeCandidate]
    new_cursor_value: str | None = None


class DemoReplayRequest(ApiModel):
    limit: int = 16
