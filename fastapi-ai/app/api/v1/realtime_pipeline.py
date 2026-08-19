from fastapi import APIRouter

from app.schemas.common import ApiResponse
from app.schemas.realtime_pipeline import DemoReplayRequest, RealtimeFetchRequest, RealtimeFetchResult, RealtimeCandidate
from app.services.realtime_gdelt_service import fetch_and_triage
from app.services.demo_gdelt_replay_service import load_demo_candidates

router = APIRouter(prefix="/api/v1/internal/realtime-pipeline", tags=["realtime-pipeline"])


@router.post("/fetch-and-triage", response_model=ApiResponse[RealtimeFetchResult])
def fetch_and_triage_route(request: RealtimeFetchRequest) -> ApiResponse[RealtimeFetchResult]:
    """Spring CollectionService.runSource("GDELT")가 호출합니다.
    GDELT 원본을 조회해 XGBoost 트리아지를 통과한 기사만 크롤링해서 반환하고,
    dedup·저장·F3 분석 트리거·커서 갱신은 Spring이 기존 로직 그대로 처리합니다."""
    candidates, new_cursor_value = fetch_and_triage(request.cursor_value)
    items = [
        RealtimeCandidate(
            global_event_id=c["global_event_id"], title=c["title"], content=c["content"],
            source_url=c["source_url"], action_geo_country_code=c.get("action_geo_country_code"),
            goldstein_scale=c.get("goldstein_scale"),
        )
        for c in candidates
    ]
    return ApiResponse(data=RealtimeFetchResult(items=items, new_cursor_value=new_cursor_value))


@router.post("/demo-replay", response_model=ApiResponse[RealtimeFetchResult])
def demo_replay_route(request: DemoReplayRequest) -> ApiResponse[RealtimeFetchResult]:
    """확정된 과거 GDELT manifest를 실시간 후보와 같은 DTO로 반환합니다."""
    items = [RealtimeCandidate(**item) for item in load_demo_candidates(request.limit)]
    return ApiResponse(data=RealtimeFetchResult(items=items, new_cursor_value=None))
