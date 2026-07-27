import os
from dataclasses import dataclass
from functools import lru_cache


@dataclass(frozen=True)
class OpenAISettings:
    """Response Agent에서 사용하는 OpenAI 설정."""

    api_key: str
    model: str
    timeout_seconds: float


@lru_cache(maxsize=1)
def get_openai_settings() -> OpenAISettings:
    """환경변수에서 OpenAI 설정을 읽는다."""

    timeout_text = os.getenv(
        "OPENAI_TIMEOUT_SECONDS",
        "30",
    )

    try:
        timeout_seconds = float(timeout_text)
    except ValueError:
        timeout_seconds = 30.0

    return OpenAISettings(
        api_key=os.getenv(
            "OPENAI_API_KEY",
            "",
        ).strip(),
        model=os.getenv(
            "OPENAI_MODEL",
            "gpt-4o-mini",
        ).strip(),
        timeout_seconds=max(
            1.0,
            timeout_seconds,
        ),
    )