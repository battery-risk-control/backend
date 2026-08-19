-- raw_events.triggered_analysis_id 인덱스 (2026-08-19, 로딩 속도 회귀 수정).
--
-- 확정 브리핑 사건 dedup(NewsEventSql.BRIEFING_DEDUP_KEY)과 completed_news 조인이
-- "이 analysis를 촉발한 최신 NEWS raw_event"를 raw_events에서 triggered_analysis_id로 찾는데,
-- 이 컬럼에 인덱스가 없어 브리핑 행마다 raw_events 전체를 seq scan 했다(실측: 서브쿼리당 ~17ms,
-- 1계층 kpi-summary 한 번에 ~1.6s). collected_at DESC를 함께 담아 ORDER BY ... LIMIT 1도 인덱스로 끝낸다.
CREATE INDEX IF NOT EXISTS idx_raw_events_triggered_analysis
    ON raw_events (triggered_analysis_id, collected_at DESC)
    WHERE triggered_analysis_id IS NOT NULL;
