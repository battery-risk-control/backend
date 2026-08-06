-- [surin 병합] F3 AI 분석 라이프사이클 테이블.
-- 출처: surin V4(create_analyses) + surin V8의 analyses 컬럼 2개 흡수, surin V10(briefing_id drop) 반영해 briefing_id 미생성.
CREATE TABLE analyses (
    analysis_id     UUID PRIMARY KEY,
    material_id     BIGINT,
    supplier_id     BIGINT,
    event_title     VARCHAR(500) NOT NULL,
    event_content   TEXT NOT NULL,
    source_name     VARCHAR(200),
    country_code    VARCHAR(2),
    status          VARCHAR(20) NOT NULL,
    impact_domain   VARCHAR(50),
    severity        VARCHAR(20),
    severity_score  DOUBLE PRECISION,
    confidence      DOUBLE PRECISION,
    reason_codes    TEXT,
    rule_version    VARCHAR(100),
    material_category      VARCHAR(50),   -- surin V8에서 흡수
    recommendation_caveats TEXT,          -- surin V8에서 흡수
    mock            BOOLEAN NOT NULL DEFAULT FALSE,
    error_code      VARCHAR(100),
    error_message   VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_analyses_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_analyses_status ON analyses (status);
