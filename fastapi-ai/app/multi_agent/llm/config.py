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

    max_tokens_text = os.getenv(
        "ANTHROPIC_MAX_TOKENS",
        "4096",
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
