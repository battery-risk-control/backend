-- [surin 병합] F3 분석 결과의 F9 대체 공급사 추천 이력.
-- 출처: surin V8(create_analysis_supplier_recommendations). analyses ALTER 2줄은 V9로 이동, 테이블 정의만 유지.
CREATE TABLE analysis_supplier_recommendations (
    id BIGSERIAL PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analyses (analysis_id),
    supplier_id BIGINT NOT NULL,
    supplier_code VARCHAR(50) NOT NULL,
    supplier_name VARCHAR(150) NOT NULL,
    rank_position INTEGER NOT NULL,
    pros TEXT,
    cons TEXT,
    recommendation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_analysis_supplier_rank UNIQUE (analysis_id, rank_position)
);
CREATE INDEX idx_asr_analysis ON analysis_supplier_recommendations (analysis_id);

COMMENT ON TABLE analysis_supplier_recommendations IS 'F3 분석 시 자동 계산된 F9 대체 공급사 추천 이력(감사·재조회용)';
