from typing import Literal

# Supervisor가 선택할 수 있는 다음 실행 경로
SupervisorRoute = Literal[
    "erp",          ## ERP Agent 실행
    "contract",     ##Contract RAG Agent 실행
    "erp_recheck",
    "risk",         ##최종 구매 리스크 계산
    "response",     ##대응방안과 브리핑 생성
    "reviewer",     ##결과 검증
    "finish"        ##작업 종료
]

MAX_RETRY_COUNT = 2
