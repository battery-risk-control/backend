-- [롤백 전용 — 자동 실행되지 않는다] V38__add_raw_events_material_matched.sql 되돌리기.
--
-- 이 디렉터리(db/rollback)는 Flyway 스캔 경로(classpath:db/migration) 밖이라 부팅 시 실행되지
-- 않는다. V38의 material_matched 생성 컬럼/인덱스를 물리적으로 걷어내야 할 때만 손으로 돌린다.
--
-- 주의: 대개는 여기까지 올 필요가 없다. V38은 컬럼·인덱스를 '추가'만 하므로, 구버전 Java
-- 코드(정규식 쿼리)도 이 스키마에서 그대로 동작한다. 문제가 생기면 DB는 그대로 두고 Java만
-- 되돌리는 것이 1차 롤백 경로다. 이 스크립트는 스키마까지 완전히 되돌리는 2차 경로다.
--
-- 실행 예:
--   docker exec battery-risk-postgres psql -U battery_app -d battery_risk \
--     -f /path/to/R38__drop_raw_events_material_matched.sql
--
-- Flyway 이력에서 V38 기록도 지우려면(재적용을 원할 때) 아래 DELETE 주석을 함께 푼다.

DROP INDEX IF EXISTS idx_raw_events_supply_chain_news;
ALTER TABLE raw_events DROP COLUMN IF EXISTS material_matched;

-- DELETE FROM flyway_schema_history WHERE version = '38';
