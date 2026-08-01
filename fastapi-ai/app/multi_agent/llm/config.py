import os
from dataclasses import dataclass
from functools import lru_cache


@dataclass(frozen=True)
class AnthropicSettings:
    """Response Agent에서 사용하는 Claude(Anthropic) 설정."""

    api_key: str
    model: str
    timeout_seconds: float
    max_tokens: int


@lru_cache(maxsize=1)
def get_anthropic_settings() -> AnthropicSettings:
    """환경변수에서 Anthropic 설정을 읽는다."""

    timeout_text = os.getenv(
        "ANTHROPIC_TIMEOUT_SECONDS",
        "30",
    )

    try:
        timeout_seconds = float(timeout_text)
    except ValueError:
        timeout_seconds = 30.0

    # extended thinking이 이 예산을 함께 소비한다. 콩고+코발트 실증에서 4096으로는
    # thinking이 4095를 다 써버려 구조화 출력이 통째로 잘리고(stop_reason=max_tokens)
    # ValidationError로 이어졌다(2026-07-31). 8192는 같은 입력에서 thinking 3176 +
    # output 5325로 여유 있게 끝난다.
    max_tokens_text = os.getenv(
        "ANTHROPIC_MAX_TOKENS",
        "8192",
    )

    try:
        max_tokens = int(max_tokens_text)
    except ValueError:
        max_tokens = 4096

    return AnthropicSettings(
        api_key=os.getenv(
            "ANTHROPIC_API_KEY",
            "",
        ).strip(),
        model=os.getenv(
            "ANTHROPIC_MODEL",
            "claude-sonnet-5",
        ).strip(),
        timeout_seconds=max(
            1.0,
            timeout_seconds,
        ),
        max_tokens=max(
            1,
            max_tokens,
        ),
    )
