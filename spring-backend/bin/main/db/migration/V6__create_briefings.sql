CREATE TABLE briefings (
    briefing_id UUID PRIMARY KEY,
    material_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    erp_material_id VARCHAR(40) NOT NULL,
    erp_supplier_id VARCHAR(40) NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    severity VARCHAR(20) NOT NULL,
    score NUMERIC(5, 1) NOT NULL,
    headline VARCHAR(300) NOT NULL,
    risk_summary TEXT NOT NULL,
    inventory_summary TEXT NOT NULL,
    supplier_dependency_summary TEXT NOT NULL,
    supply_gap_summary TEXT NOT NULL,
    contract_evidence_summary TEXT NOT NULL,
    alternative_supplier_summary TEXT NOT NULL,
    reason_codes JSONB NOT NULL,
    recommended_checks JSONB NOT NULL,
    warnings JSONB NOT NULL,
    contract_evidence JSONB NOT NULL,
    alternative_suppliers JSONB NOT NULL,
    template_version VARCHAR(50) NOT NULL,
    rule_version VARCHAR(50) NOT NULL,
    mock BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_briefing_material
        FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT fk_briefing_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id),
    CONSTRAINT ck_briefing_severity
        CHECK (severity IN ('NORMAL', 'WARNING', 'CRITICAL', 'UNKNOWN')),
    CONSTRAINT ck_briefing_score
        CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_briefing_material_created
    ON briefings (material_id, created_at DESC);

CREATE INDEX idx_briefing_severity_created
    ON briefings (severity, created_at DESC);

COMMENT ON TABLE briefings IS
    'S13 템플릿 브리핑 본문과 계약 근거·대체 공급사 참조 스냅샷';
