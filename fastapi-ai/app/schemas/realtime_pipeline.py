from app.schemas.common import ApiModel


class RealtimeFetchRequest(ApiModel):
    cursor_value: str | None = None


class RealtimeCandidate(ApiModel):
    global_event_id: str
    title: str
    content: str
    source_url: str
    action_geo_country_code: str | None = None


class RealtimeFetchResult(ApiModel):
    items: list[RealtimeCandidate]
    new_cursor_value: str | None = None
