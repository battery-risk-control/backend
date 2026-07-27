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
- **실행 전 준비물**:
  1. `.env` 파일을 이 폴더(`realtime-pipeline/`) 바로 아래에 직접 만들고
     `OPENAI_API_KEY=본인키`를 넣어야 함 (git에 안 올라감, 각자 자기 키로 생성).
  2. `데이터셋/BDI 2020.01.01-2026.07.14.csv`는 **커밋되어 있음**(109KB) —
     `build_features.py`가 예외처리 없이 바로 읽어서 이거 없으면 무조건 크래시남.
  3. `data_core/`, `data_ref/`는 **의도적으로 안 올렸음** — `realtime_risk_pipeline.py`가
     처음 실행될 때 `cleaned_labeled_articles.csv`/`gdelt_event_metadata.csv`를 스스로
     만들어서 채움(파일 없으면 새로 생성하도록 짜여있음). 미리 올리면 오히려 내 로컬
     누적 데이터로 팀원 로컬을 덮어쓰는 문제가 생길 수 있어서 각자 로컬에서 새로 쌓이게
     둠.
  4. `데이터셋/openmeteo_*/*.json`(강수량 원본, 172MB)은 안 올림 — 코드가 파일 없으면
     조용히 빈 값으로 폴백하고, 지금 심각도 공식 자체가 강수량을 안 쓰고 있어서(2026-07-24
     결정) 없어도 무방함.
  5. GDACS(재난경보)는 이제 정적 파일이 아니라 `src/gdacs_live.py`가 매 실행마다 실시간
     API로 가져와서 별도 파일 준비 불필요.
  6. `processed_events.db`(SQLite, 중복 크롤링 방지용 상태)도 없으면 첫 실행 때 자동
     생성됨.

## FastAPI 통합 시 고려할 것

- 트리거를 Windows 작업 스케줄러 → Spring Scheduler로 교체 필요.
- 이 스크립트의 로직을 FastAPI `/api/v1/analyze` 서비스 레이어로 옮기거나, 최소한 그
  서비스가 호출하는 형태로 감싸야 함.
- 상태 저장을 로컬 SQLite/CSV → PostgreSQL(Spring이 관리)로 전환 필요.
- GDACS/OpenMeteo/yfinance 등 외부 API 호출 로직(`src/gdacs_live.py`,
  `data_prep/build_features.py`)은 로직 자체는 재사용 가능하나, PostgreSQL 스키마에 맞게
  저장 방식만 갈아끼우면 될 것으로 보임.
