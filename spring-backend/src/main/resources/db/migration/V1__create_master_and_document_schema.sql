CREATE TABLE materials (
    material_id BIGSERIAL PRIMARY KEY,
    material_code VARCHAR(50) NOT NULL,
    material_name VARCHAR(100) NOT NULL,
    material_name_en VARCHAR(100),
    material_grade VARCHAR(50),
    base_unit VARCHAR(20) NOT NULL DEFAULT 'KG',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_material_code UNIQUE (material_code)
);

CREATE TABLE suppliers (
    supplier_id BIGSERIAL PRIMARY KEY,
    supplier_code VARCHAR(50) NOT NULL,
    supplier_name VARCHAR(150) NOT NULL,
    business_registration_number VARCHAR(30),
    country_code CHAR(2) NOT NULL,
    feoc_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    iatf_16949_certified BOOLEAN NOT NULL DEFAULT FALSE,
    ppap_approved BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_supplier_code UNIQUE (supplier_code),
    CONSTRAINT uq_supplier_registration UNIQUE (business_registration_number),
    CONSTRAINT ck_supplier_feoc_status
        CHECK (feoc_status IN ('YES', 'NO', 'UNKNOWN'))
);

CREATE TABLE contracts (
    contract_id BIGSERIAL PRIMARY KEY,
    contract_number VARCHAR(80) NOT NULL,
    supplier_id BIGINT NOT NULL,
    contract_name VARCHAR(200) NOT NULL,
    start_date DATE,
    end_date DATE,
    currency_code CHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contract_number UNIQUE (contract_number),
    CONSTRAINT uq_contract_supplier_pair UNIQUE (contract_id, supplier_id),
    CONSTRAINT fk_contract_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id),
    CONSTRAINT ck_contract_period
        CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_contract_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRED', 'TERMINATED'))
);

CREATE TABLE contract_documents (
    document_id UUID PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    document_type VARCHAR(50) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    chunk_count INTEGER NOT NULL DEFAULT 0,
    embedding_type VARCHAR(50),
    embedding_version VARCHAR(50),
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    CONSTRAINT fk_document_contract_supplier
        FOREIGN KEY (contract_id, supplier_id)
        REFERENCES contracts (contract_id, supplier_id),
    CONSTRAINT fk_document_material
        FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT uq_contract_document_hash UNIQUE (contract_id, content_hash),
    CONSTRAINT ck_document_file_size CHECK (file_size_bytes > 0),
    CONSTRAINT ck_document_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_document_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_document_type
        CHECK (document_type IN (
            'LTA',
            'PURCHASE_GUIDELINE',
            'SUPPLIER_EVALUATION',
            'QUALITY_CERTIFICATE',
            'REGULATION',
            'TECHNICAL_SPEC'
        ))
);

CREATE INDEX idx_contract_supplier ON contracts (supplier_id);
CREATE INDEX idx_document_contract ON contract_documents (contract_id);
CREATE INDEX idx_document_supplier ON contract_documents (supplier_id);
CREATE INDEX idx_document_material ON contract_documents (material_id);
CREATE INDEX idx_document_status ON contract_documents (processing_status);
CREATE INDEX idx_document_created_at ON contract_documents (created_at DESC);

COMMENT ON TABLE materials IS '배터리 원자재 Master Data';
COMMENT ON TABLE suppliers IS '공급사 Master Data';
COMMENT ON TABLE contracts IS '공급사 장기·구매 계약 Metadata';
COMMENT ON TABLE contract_documents IS '원본 계약·지침 파일과 FastAPI 처리 상태 Metadata';
