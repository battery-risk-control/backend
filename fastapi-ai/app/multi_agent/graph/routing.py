from typing import Literal


# Supervisor가 선택할 수 있는 다음 실행 경로
SupervisorRoute = Literal[
    "kg",
    "erp",
    "contract",
    "erp_recheck",
    "risk",
    "response",
    "reviewer",
    "finish",
]


# Reviewer 검증 실패 시 허용할 최대 재시도 횟수
MAX_RETRY_COUNT = 2