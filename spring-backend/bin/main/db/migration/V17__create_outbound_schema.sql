-- 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 스키마. 기존 인바운드 contracts/contract_documents
-- 테이블·제약·자바 코드 경로는 전혀 건드리지 않고, 완전히 병렬인 신규 테이블 세트로 분리한다
-- (CLAUDE.md 결정사항 10번, 다음 세션 시작 지점 3번).

CREATE TABLE products (
    product_id BIGSERIAL PRIMARY KEY,
    erp_product_id VARCHAR(30) NOT NULL,
    name_en VARCHAR(150) NOT NULL,
    name_kr VARCHAR(150) NOT NULL,
    product_line VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_erp_id UNIQUE (erp_product_id)
);

CREATE TABLE product_compositions (
    product_composition_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    material_category VARCHAR(50) NOT NULL,
    CONSTRAINT fk_product_composition_product
        FOREIGN KEY (product_id) REFERENCES products (product_id),
    CONSTRAINT uq_product_composition UNIQUE (product_id, material_category)
);

CREATE TABLE customers (
    customer_id BIGSERIAL PRIMARY KEY,
    erp_customer_id VARCHAR(30) NOT NULL,
    name_en VARCHAR(150) NOT NULL,
    name_kr VARCHAR(150) NOT NULL,
    country_code CHAR(2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_erp_id UNIQUE (erp_customer_id)
);

-- product_id+customer_id 조합에 DB unique 제약을 걸지 않는다 — 인바운드가 supplier_materials
-- 조인으로 애플리케이션 레벨 dedupe만 하는 것과 같은 철학(재계약 등으로 같은 조합에 계약이
-- 여러 개 필요해져도 마이그레이션 없이 대응 가능).
CREATE TABLE outbound_contracts (
    outbound_contract_id BIGSERIAL PRIMARY KEY,
    erp_outbound_contract_id VARCHAR(30) NOT NULL,
    product_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    seller VARCHAR(150) NOT NULL DEFAULT 'LG에너지솔루션',
    quantity_gwh NUMERIC(10, 2) NOT NULL,
    unit_price_usd_kwh NUMERIC(10, 2) NOT NULL,
    penalty_pct NUMERIC(5, 2) NOT NULL,
    line_stop_charge_usd NUMERIC(14, 2),
    line_stop_charge_krw NUMERIC(16, 2),
    delivery_lead_time_days INTEGER NOT NULL,
    contract_language VARCHAR(2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_outbound_contract_erp_id UNIQUE (erp_outbound_contract_id),
    CONSTRAINT fk_outbound_contract_product
        FOREIGN KEY (product_id) REFERENCES products (product_id),
    CONSTRAINT fk_outbound_contract_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id),
    CONSTRAINT ck_outbound_contract_language CHECK (contract_language IN ('EN', 'KR')),
    CONSTRAINT ck_outbound_contract_quantity CHECK (quantity_gwh > 0),
    CONSTRAINT ck_outbound_contract_unit_price CHECK (unit_price_usd_kwh > 0),
    CONSTRAINT ck_outbound_contract_penalty CHECK (penalty_pct >= 0),
    CONSTRAINT ck_outbound_contract_lead_time CHECK (delivery_lead_time_days > 0)
);

-- contract_documents와 거의 동일한 구조(RAG/ChromaDB 임베딩 포함 스코프이므로 processing_status/
-- chunk_count/embedding_* 전부 필요) — 단 인바운드 테이블은 전혀 건드리지 않고 완전히 별도로 둔다.
CREATE TABLE outbound_contract_documents (
    document_id VARCHAR(40) PRIMARY KEY,
    outbound_contract_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
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
    CONSTRAINT fk_outbound_document_contract
        FOREIGN KEY (outbound_contract_id) REFERENCES outbound_contracts (outbound_contract_id),
    CONSTRAINT fk_outbound_document_product
        FOREIGN KEY (product_id) REFERENCES products (product_id),
    CONSTRAINT fk_outbound_document_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id),
    CONSTRAINT uq_outbound_contract_document_hash UNIQUE (outbound_contract_id, content_hash),
    CONSTRAINT ck_outbound_document_file_size CHECK (file_size_bytes > 0),
    CONSTRAINT ck_outbound_document_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_outbound_document_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_outbound_document_type
        CHECK (document_type IN (
            'CONTRACT',
            'PURCHASE_ORDER',
            'SPECIFICATION',
            'CERTIFICATE',
            'OTHER'
        ))
);

CREATE INDEX idx_outbound_contract_product ON outbound_contracts (product_id);
CREATE INDEX idx_outbound_contract_customer ON outbound_contracts (customer_id);
CREATE INDEX idx_outbound_document_contract ON outbound_contract_documents (outbound_contract_id);
CREATE INDEX idx_outbound_document_product ON outbound_contract_documents (product_id);
CREATE INDEX idx_outbound_document_customer ON outbound_contract_documents (customer_id);
CREATE INDEX idx_outbound_document_status ON outbound_contract_documents (processing_status);

COMMENT ON TABLE products IS '완제품(배터리 셀/팩/모듈 등) Master Data — 아웃바운드 전용';
COMMENT ON TABLE product_compositions IS '제품->원자재 카테고리 조성(long format), data_ref/outbound_mock/product_composition.csv 대응';
COMMENT ON TABLE customers IS '완성차/ESS 고객사 Master Data — 아웃바운드 전용';
COMMENT ON TABLE outbound_contracts IS 'LG에너지솔루션->고객사 완제품 판매 계약. product_id+customer_id 직접 조합(인바운드의 supplier_materials 같은 별도 junction 테이블 없음)';
COMMENT ON TABLE outbound_contract_documents IS '아웃바운드 계약 원본 파일 + FastAPI RAG 처리 상태. contract_documents와 동일 구조, 완전히 별도 테이블';
