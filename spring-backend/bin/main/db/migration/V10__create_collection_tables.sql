-- [surin 병합] F4 외부 데이터 주기 수집 테이블.
-- 출처: surin V5(create_collection_tables) 그대로. triggered_analysis_id는 UUID 컬럼일 뿐 FK 제약 없음(순서 무관).
CREATE TABLE collection_cursors (
    source           VARCHAR(50) PRIMARY KEY,
    last_success_at  TIMESTAMP WITH TIME ZONE,
    cursor_value     VARCHAR(200)
);

CREATE TABLE raw_events (
    id                     BIGSERIAL PRIMARY KEY,
    source                 VARCHAR(50) NOT NULL,
    data_type              VARCHAR(20) NOT NULL,
    external_id            VARCHAR(200),
    content_hash           VARCHAR(64) NOT NULL,
    title                  VARCHAR(500),
    content                TEXT,
    source_url             VARCHAR(500),
    country_code           VARCHAR(2),
    payload_json           TEXT,
    triggered_analysis_id  UUID,
    collected_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_raw_events_source_external_id
    ON raw_events (source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_raw_events_source_hash ON raw_events (source, content_hash);
CREATE INDEX idx_raw_events_data_type ON raw_events (data_type);
CREATE INDEX idx_raw_events_country ON raw_events (country_code);
