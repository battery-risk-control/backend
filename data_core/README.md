# data_core

XGBoost 사전필터(triage)/severity 스코어링 파이프라인의 누적 데이터. 지금까지 어떤 git
저장소에도 커밋된 적이 없어서(프로젝트 루트가 git 저장소가 아니었음), `kg_service`와 같은 이유로
`backend/`에 이전했다 — 조원이 파이프라인을 재현·검증하려면 필요한 데이터라서.

- `cleaned_labeled_articles.csv` — LLM 라벨링 원본(`GlobalEventID`, `is_supply_chain_relevant`,
  `impact_domain_draft`, `country`, `summary_kr`, `affected_material`, `tone_score`,
  `event_type` 등)
- `event_features.csv` — 위 파일 + GDELT 원시 메타데이터(`GoldsteinScale`/`NumArticles`/
  `AvgTone` 등) + BDI/yfinance/OpenMeteo/GDACS 피처 병합 결과
- `event_features_with_clean_type.csv` — `event_features.csv` + `event_type_clean`(GPT-4o mini로
  정규화한 12개 표준 카테고리) 추가
- `event_features_normalized.csv` — 심각도 스코어링(`src/severity_scoring.py`) 결과까지 반영된
  최종본. `severity_tier`(해당없음/정상/주의/심각), `severity_score`, `priority_score` 등 포함.
  실제 GoldsteinScale 값을 가진 과거 사례를 찾아 `POST /api/v1/collection/test-news`의
  `goldstein_scale` 필드로 재현 검증할 때 이 파일을 참고하면 된다.
- `event_type_mapping_cache.json` — `event_type` 원본→표준 카테고리 정규화 캐시(반복 실행 시
  재사용, GPT-4o mini 재호출 비용 절감)
- `yfinance_price_cache.csv` — 주가 변동성 피처 계산용 캐시

원본 생성 파이프라인(`build_features.py`, `normalize_event_type.py`, `severity_scoring.py`,
`train_triage_filter.py` 등)은 프로젝트 루트 `src/`에 있으며, 이번 이전 범위에는 포함하지
않았다(KG가 직접 의존하는 `build_ontology_graph.py`/`material_category_mapper.py`만 이전함).
전체 파이프라인 재현이 필요하면 별도로 옮겨야 한다.
