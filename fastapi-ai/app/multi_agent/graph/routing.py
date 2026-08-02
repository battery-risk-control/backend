from typing import Literal


# Supervisor가 선택할 수 있는 다음 실행 경로
SupervisorRoute = Literal[
    "kg",
    "erp",
    "contract",
    "erp_recheck",
    "risk",
    "outbound_contract",
    "response",
    "reviewer",
    "finish",
]


# Reviewer 검증 실패 시 허용할 최대 재시도 횟수
MAX_RETRY_COUNT = 2

# ERP↔Contract 협상 루프가 허용할 최대 왕복 라운드 수.
# verification의 MAX_RETRY_COUNT와 같은 안전장치 패턴 — 라운드 상한과
# recheck_soojung_erp_node의 수렴 조건(점수 변화 <5) 둘 다 있어야
# 무한루프 없이 "서로 물어보며 점수가 실제로 바뀌는" 루프가 끝난다.
MAX_NEGOTIATION_ROUNDS = 2