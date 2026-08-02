import os
from dataclasses import dataclass
from functools import lru_cache


def _as_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


@lru_cache(maxsize=1)
def use_claude_for_briefing() -> bool:
    """
    브리핑 문장 생성에 Claude를 쓸지(True) OpenAI를 쓸지(False).

    기본 True — 프롬프트(`SYSTEM_PROMPT`)와 `ANTHROPIC_MAX_TOKENS` 실측이 모두 Claude
    기준으로 잡혀 있어, 그쪽이 운영 경로다.

    False로 두면 같은 페이로드·같은 출력 스키마로 OpenAI를 호출한다. Anthropic 키가 아직
    없을 때 브리핑 경로(페이로드 조립 → 구조화 출력 → DB 적재)를 확인하는 용도다.
    ⚠️ **다만 이 경로로는 thinking 토큰 문제를 검증하지 못한다.** 2026-07-31에 실제로
    터졌던 버그(thinking이 max_tokens를 다 써서 구조화 출력이 잘림)는 extended thinking이
    있는 Claude에서만 재현되므로, OpenAI로 초록불이 떠도 Claude 경로의 보증이 아니다.
    """

    return _as_bool(os.getenv("BRIEFING_USE_CLAUDE", "true"))


@dataclass(frozen=True)
class OpenAIBriefingSettings:
    """브리핑 생성을 OpenAI로 돌릴 때 쓰는 설정."""

    api_key: str
    model: str
    timeout_seconds: float


@lru_cache(maxsize=1)
def get_openai_briefing_settings() -> OpenAIBriefingSettings:
    """
    환경변수에서 OpenAI 브리핑 설정을 읽는다.

    추출·번역이 이미 쓰고 있는 `OPENAI_API_KEY`·`OPENAI_MODEL`을 그대로 재사용한다 —
    같은 계정의 같은 키라 브리핑용으로 따로 발급할 이유가 없다. 브리핑만 다른 모델로
    돌리고 싶으면 `BRIEFING_OPENAI_MODEL`로 덮어쓴다.

    타임아웃은 Anthropic 쪽과 같은 값을 쓴다. 프롬프트와 페이로드가 동일해서 응답 시간
    특성도 비슷하고, 여기만 다른 값을 두면 어느 쪽이 느린지 비교가 안 된다.
    """

    timeout_text = os.getenv("ANTHROPIC_TIMEOUT_SECONDS", "30")
    try:
        timeout_seconds = float(timeout_text)
    except ValueError:
        timeout_seconds = 30.0

    # 빈 문자열을 "미설정"으로 취급한다 — docker-compose가 ${BRIEFING_OPENAI_MODEL:-}로
    # 항상 키를 넘기므로, os.getenv의 기본값 인자에 맡기면 폴백이 영영 안 걸린다.
    override = os.getenv("BRIEFING_OPENAI_MODEL", "").strip()
    model = override or os.getenv("OPENAI_MODEL", "").strip() or "gpt-4o-mini"

    return OpenAIBriefingSettings(
        api_key=os.getenv("OPENAI_API_KEY", "").strip(),
        model=model,
        timeout_seconds=max(1.0, timeout_seconds),
    )


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
