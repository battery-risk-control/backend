from functools import lru_cache

from anthropic import Anthropic

from app.multi_agent.llm.config import (
    get_anthropic_settings,
)


@lru_cache(maxsize=1)
def get_anthropic_client() -> Anthropic:
    """
    Anthropic 클라이언트를 한 번 생성하여 재사용한다.

    API 키가 없다면 실제 LLM 호출 전에 명확한 오류를 발생시킨다.
    """

    settings = get_anthropic_settings()

    if not settings.api_key:
        raise RuntimeError(
            "ANTHROPIC_API_KEY가 설정되지 않았습니다."
        )

    return Anthropic(
        api_key=settings.api_key,
        timeout=settings.timeout_seconds,
    )


def get_anthropic_model() -> str:
    """환경변수에 설정된 Claude 모델명을 반환한다."""

    settings = get_anthropic_settings()

    if not settings.model:
        return "claude-sonnet-5"

    return settings.model


def get_anthropic_max_tokens() -> int:
    """브리핑 응답 생성에 쓸 max_tokens 값을 반환한다."""

    return get_anthropic_settings().max_tokens


def reset_anthropic_client() -> None:
    """테스트나 설정 변경 시 캐시된 클라이언트를 초기화한다."""

    get_anthropic_client.cache_clear()
    get_anthropic_settings.cache_clear()
