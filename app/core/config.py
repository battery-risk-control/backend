import os
from dataclasses import dataclass

from dotenv import load_dotenv


# 프로젝트 루트의 .env 파일을 환경변수로 불러온다.
load_dotenv()


@dataclass(frozen=True)
class OpenAISettings:
    api_key: str
    model: str
    timeout_seconds: float


def get_openai_settings() -> OpenAISettings:
    """
    OpenAI 관련 환경변수를 읽어 설정 객체로 반환한다.

    이 함수는 API 키를 출력하거나 로그에 기록하지 않는다.
    """
    api_key = os.getenv(
        "OPENAI_API_KEY",
        "",
    ).strip()

    model = os.getenv(
        "OPENAI_MODEL",
        "gpt-4o-mini",
    ).strip()

    timeout_value = os.getenv(
        "OPENAI_TIMEOUT_SECONDS",
        "30",
    ).strip()

    try:
        timeout_seconds = float(timeout_value)
    except ValueError as error:
        raise ValueError(
            "OPENAI_TIMEOUT_SECONDS는 숫자여야 합니다."
        ) from error

    if timeout_seconds <= 0:
        raise ValueError(
            "OPENAI_TIMEOUT_SECONDS는 0보다 커야 합니다."
        )

    return OpenAISettings(
        api_key=api_key,
        model=model,
        timeout_seconds=timeout_seconds,
    )
