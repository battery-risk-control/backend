from functools import lru_cache

from openai import OpenAI

from app.core.config import get_openai_settings


@lru_cache(maxsize=1)
def get_openai_client() -> OpenAI:
    """
    OpenAI 클라이언트를 한 번 생성한 뒤 재사용한다.

    API 키가 없으면 실제 LLM 호출 전에 명확한 오류를 발생시킨다.
    """
    settings = get_openai_settings()

    if not settings.api_key:
        raise RuntimeError(
            "OPENAI_API_KEY가 설정되지 않았습니다."
        )

    return OpenAI(
        api_key=settings.api_key,
        timeout=settings.timeout_seconds,
    )


def get_openai_model() -> str:
    """환경변수에 설정된 OpenAI 모델명을 반환한다."""
    settings = get_openai_settings()

    return settings.model
