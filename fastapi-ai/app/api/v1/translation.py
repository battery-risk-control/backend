from fastapi import APIRouter

from app.schemas.common import ApiResponse
from app.schemas.translation import TranslateTitlesRequest, TranslateTitlesResult
from app.services.translation_service import translate_titles

router = APIRouter(prefix="/api/v1/internal/translation", tags=["translation"])


@router.post("/titles", response_model=ApiResponse[TranslateTitlesResult])
def translate_titles_route(request: TranslateTitlesRequest) -> ApiResponse[TranslateTitlesResult]:
    """Spring TranslationService의 스케줄러가 호출합니다.

    아직 번역되지 않은 영문 뉴스 제목을 모아 보내면 한 번의 LLM 호출로 묶어 번역해 돌려줍니다.
    저장은 Spring이 raw_events.title_ko에 합니다 — FastAPI는 DB에 접근하지 않습니다.

    요청보다 적게 돌아올 수 있습니다(키 없음·호출 실패·LLM 누락). 돌아온 것만 반영하고
    나머지는 다음 주기에 다시 보내면 됩니다.
    """
    return ApiResponse(data=translate_titles(request.items))
