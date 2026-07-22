CREATE TABLE severity_assessments (
    assessment_id UUID PRIMARY KEY,
    material_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    erp_material_id VARCHAR(40) NOT NULL,
    erp_supplier_id VARCHAR(40) NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    inventory_days NUMERIC(14, 4),
    safety_stock_days NUMERIC(14, 4),
    expected_supply_gap_days NUMERIC(14, 4),
    supplier_dependency_ratio NUMERIC(8, 6),
    price_change_rate NUMERIC(14, 4),
    logistics_delay_days NUMERIC(14, 4),
    gdacs_alert_level INTEGER,
    feoc_status VARCHAR(20) NOT NULL,
    data_quality_status VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    score NUMERIC(5, 1) NOT NULL,
    reason_codes JSONB NOT NULL,
    calculation_details JSONB NOT NULL,
    rule_version VARCHAR(50) NOT NULL,
    mock BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_severity_material
        FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT fk_severity_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id),
    CONSTRAINT ck_severity_inventory_days
        CHECK (inventory_days IS NULL OR inventory_days >= 0),
    CONSTRAINT ck_severity_safety_days
        CHECK (safety_stock_days IS NULL OR safety_stock_days >= 0),
    CONSTRAINT ck_severity_supply_gap
        CHECK (expected_supply_gap_days IS NULL OR expected_supply_gap_days >= 0),
    CONSTRAINT ck_severity_dependency
        CHECK (supplier_dependency_ratio IS NULL OR supplier_dependency_ratio BETWEEN 0 AND 1),
    CONSTRAINT ck_severity_logistics_delay
        CHECK (logistics_delay_days IS NULL OR logistics_delay_days >= 0),
    CONSTRAINT ck_severity_gdacs
        CHECK (gdacs_alert_level IS NULL OR gdacs_alert_level BETWEEN 0 AND 2),
    CONSTRAINT ck_severity_feoc
        CHECK (feoc_status IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT ck_severity_data_quality
        CHECK (data_quality_status IN ('VALID', 'STALE', 'INCOMPLETE', 'INVALID', 'UNKNOWN')),
    CONSTRAINT ck_severity_level
        CHECK (severity IN ('NORMAL', 'WARNING', 'CRITICAL', 'UNKNOWN')),
    CONSTRAINT ck_severity_score
        CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_severity_material_created
    ON severity_assessments (material_id, created_at DESC);

CREATE INDEX idx_severity_level_created
    ON severity_assessments (severity, created_at DESC);

COMMENT ON TABLE severity_assessments IS
    'Spring ERP/external signal snapshot and deterministic FastAPI Severity result';
