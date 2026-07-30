from app.schemas.common import ApiModel


class RealtimeFetchRequest(ApiModel):
    cursor_value: str | None = None


class RealtimeCandidate(ApiModel):
    global_event_id: str
    title: str
    content: str
    source_url: str
    action_geo_country_code: str | None = None
    # GDELT가 이 이벤트에 이미 계산해준 값을 그대로 전달 — Spring이 나중에 국가 단위로
    # 재조회하지 않도록 한다(재조회는 같은 URL에 이벤트가 여러 개면 값이 갈리고,
    # URL 매칭 실패 시 부정확한 기본값/국가평균으로 샌다).
    goldstein_scale: float | None = None


class RealtimeFetchResult(ApiModel):
    items: list[RealtimeCandidate]
    new_cursor_value: str | None = None
