-- [성능] 공급망 뉴스 조회의 자재 키워드 매칭을 '읽을 때 정규식 스캔'에서 '수집 때 굳혀 둔
-- 불리언 컬럼 + 부분 인덱스'로 바꾼다.
--
-- 배경: raw_events(약 9.4천 행, 본문 평균 4.2천 자)에 대해 findSupplyChainNews /
-- countSupplyChainNews / findLatestSupplyChainCollectedAt 가 요청마다
--   (coalesce(title,'') || ' ' || coalesce(content,'')) ~* '<자재 키워드들>'
-- 정규식으로 테이블을 통째로 훑었다. 실측(EXPLAIN ANALYZE, 워밍 후): 요청당 약 1.1~1.2초.
-- 정규식만 빼면 같은 스캔이 3ms라, 비용의 거의 전부가 이 행별 정규식이었다. 이 값은 행이
-- 만들어질 때 정해지고 이후 변하지 않으므로 읽을 때 계산할 이유가 없다.
--
-- 방식: GENERATED ALWAYS ... STORED 생성 컬럼. 앱의 write 경로를 전혀 건드리지 않는다 —
-- 기존 9.4천 행은 컬럼 추가 시 자동 백필되고, 이후 INSERT/UPDATE도 DB가 알아서 채운다.
-- ~* 는 IMMUTABLE이라 생성 컬럼 식으로 허용된다(PostgreSQL 16).
--
-- ┌─ 반드시 동기화 ─────────────────────────────────────────────────────────────┐
-- │ 아래 정규식의 키워드 집합은 RiskEventService.materialKeywordPattern() 이       │
-- │ MATERIAL_KEYWORDS 로 만드는 것과 **정확히 같아야** 한다(각 키워드를 \y…\y 로   │
-- │ 감싸 | 로 연결). 한쪽만 고치면 뉴스 목록·건수·기준시각이 조용히 어긋난다.       │
-- │ RiskEventServiceTest 의 동기화 가드 테스트가 두 집합이 같은지 검사한다.        │
-- │ 키워드를 추가/변경하려면: 이 파일을 복제한 새 마이그레이션에서 컬럼을           │
-- │ DROP 후 재-ADD 해야 한다(생성 컬럼 식은 ALTER 로 바꿀 수 없다).                │
-- └────────────────────────────────────────────────────────────────────────────┘
ALTER TABLE raw_events
    ADD COLUMN material_matched boolean
    GENERATED ALWAYS AS (
        (coalesce(title, '') || ' ' || coalesce(content, ''))
            ~* '\ynickel\y|\y니켈\y|\ycobalt\y|\y코발트\y|\ylithium\y|\y리튬\y|\ygraphite\y|\y흑연\y|\ymanganese\y|\y망간\y|\ycopper\y|\y구리\y|\yaluminum\y|\yaluminium\y|\y알루미늄\y|\yrare earth\y|\yrare-earth\y|\y희토류\y'
    ) STORED;

COMMENT ON COLUMN raw_events.material_matched IS
    '제목+본문에 자재 키워드가 있는지(수집 시점 계산·저장). RiskEventService.MATERIAL_KEYWORDS와 동기화 필수.';

-- 부분 인덱스: 공급망 뉴스 조회 3종의 WHERE(data_type='NEWS' AND title IS NOT NULL AND
-- material_matched)와 정렬(collected_at DESC)에 정확히 대응한다. 이 인덱스만 보면 되도록
-- 만들어 seq scan 자체를 없앤다.
CREATE INDEX idx_raw_events_supply_chain_news
    ON raw_events (collected_at DESC)
    WHERE data_type = 'NEWS' AND title IS NOT NULL AND material_matched;
