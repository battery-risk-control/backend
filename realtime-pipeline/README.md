# realtime-pipeline (임시 스테이징 — FastAPI 통합 전)

GDELT 실시간 수집 → 트리아지(XGBoost) → 크롤링 → LLM 정보추출 → 피처 병합(GDACS 실시간 조회
포함) → 심각도 스코어링까지 자동으로 이어지는 파이프라인. 현재는 **독립 Python 스크립트로
동작**하며, `backend/.claude/CLAUDE.md`가 그리는 최종 아키텍처(Spring Scheduler → FastAPI
`/api/v1/analyze` → PostgreSQL)에는 아직 편입되지 않았다.

## 현재 상태 (2026-07-27)

- 실행: `python realtime_risk_pipeline.py` (또는 `scripts/run_realtime_pipeline.ps1` 래퍼).
  로컬에서는 Windows 작업 스케줄러로 15분마다 자동 실행되도록 등록해뒀음(이 컴퓨터 전용
  로컬 설정이라 이 저장소에는 포함 안 됨).
- 상태 저장: 로컬 SQLite(`processed_events.db`, 이 폴더 실행 시 자동 생성) + 로컬 CSV
  (`data_core/cleaned_labeled_articles.csv`, `data_ref/gdelt_event_metadata.csv`,
  `data_core/event_features*.csv`) — **PostgreSQL이 아니라 파일 기반**이라 최종 아키텍처와
  다름.
- **이 폴더만으로는 바로 실행이 안 됨**: `build_features.py`가 필요로 하는 데이터 파일
  (`데이터셋/BDI *.csv`, `데이터셋/openmeteo_*/*.json`, `data_core/`, `data_ref/`)이 이
  저장소에는 없음 — AI 파이프라인 프로젝트 루트(`빅프로젝트/`)에만 있고 용량 문제로 git에
  올리지 않았음. 지금은 코드 스냅샷만 백업/공유하는 목적으로 커밋함.

## FastAPI 통합 시 고려할 것

- 트리거를 Windows 작업 스케줄러 → Spring Scheduler로 교체 필요.
- 이 스크립트의 로직을 FastAPI `/api/v1/analyze` 서비스 레이어로 옮기거나, 최소한 그
  서비스가 호출하는 형태로 감싸야 함.
- 상태 저장을 로컬 SQLite/CSV → PostgreSQL(Spring이 관리)로 전환 필요.
- GDACS/OpenMeteo/yfinance 등 외부 API 호출 로직(`src/gdacs_live.py`,
  `data_prep/build_features.py`)은 로직 자체는 재사용 가능하나, PostgreSQL 스키마에 맞게
  저장 방식만 갈아끼우면 될 것으로 보임.
