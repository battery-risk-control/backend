ALTER TABLE materials
    ADD COLUMN erp_material_id VARCHAR(40),
    ADD COLUMN material_category VARCHAR(40),
    ADD COLUMN criticality VARCHAR(20),
    ADD COLUMN erp_group_code VARCHAR(40);

ALTER TABLE materials
    ADD CONSTRAINT uq_material_erp_id UNIQUE (erp_material_id),
    ADD CONSTRAINT ck_material_criticality
        CHECK (criticality IS NULL OR criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

ALTER TABLE suppliers
    ADD COLUMN erp_supplier_id VARCHAR(40),
    ADD COLUMN supplier_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN risk_level VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN certifications VARCHAR(500);

ALTER TABLE suppliers
    ADD CONSTRAINT uq_supplier_erp_id UNIQUE (erp_supplier_id),
    ADD CONSTRAINT ck_supplier_status
        CHECK (supplier_status IN ('ACTIVE', 'UNDER_REVIEW', 'SUSPENDED', 'INACTIVE'));

ALTER TABLE contracts
    DROP CONSTRAINT ck_contract_status;

ALTER TABLE contracts
    ADD COLUMN erp_contract_id VARCHAR(40),
    ADD COLUMN material_id BIGINT,
    ADD COLUMN document_source VARCHAR(40),
    ADD COLUMN document_path VARCHAR(500),
    ADD COLUMN source_document_id VARCHAR(80),
    ADD COLUMN contract_role VARCHAR(30),
    ADD COLUMN supplier_approval_status VARCHAR(30),
    ADD COLUMN source_indexed_at TIMESTAMPTZ,
    ADD CONSTRAINT uq_contract_erp_id UNIQUE (erp_contract_id),
    ADD CONSTRAINT fk_contract_material
        FOREIGN KEY (material_id) REFERENCES materials (material_id),
    ADD CONSTRAINT ck_contract_status
        CHECK (status IN ('DRAFT', 'PENDING', 'ACTIVE', 'EXPIRING', 'EXPIRED', 'SUSPENDED', 'TERMINATED')),
    ADD CONSTRAINT ck_contract_role
        CHECK (contract_role IS NULL OR contract_role IN ('PRIMARY', 'ALTERNATIVE')),
    ADD CONSTRAINT ck_contract_supplier_approval
        CHECK (supplier_approval_status IS NULL OR supplier_approval_status IN ('APPROVED', 'CONDITIONAL', 'PENDING', 'REJECTED'));

CREATE TABLE warehouses (
    warehouse_id BIGSERIAL PRIMARY KEY,
    erp_warehouse_id VARCHAR(40) NOT NULL,
    warehouse_code VARCHAR(40) NOT NULL,
    warehouse_name VARCHAR(150) NOT NULL,
    country_code CHAR(2) NOT NULL,
    timezone VARCHAR(60) NOT NULL,
    warehouse_type VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_warehouse_erp_id UNIQUE (erp_warehouse_id),
    CONSTRAINT uq_warehouse_code UNIQUE (warehouse_code)
);

CREATE TABLE supplier_materials (
    supplier_material_id BIGSERIAL PRIMARY KEY,
    erp_supplier_material_id VARCHAR(40) NOT NULL,
    supplier_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    supply_share_ratio NUMERIC(8, 6) NOT NULL,
    lead_time_days INTEGER NOT NULL,
    minimum_order_quantity NUMERIC(20, 4) NOT NULL,
    approved_status VARCHAR(30) NOT NULL,
    priority_rank INTEGER NOT NULL,
    is_alternative BOOLEAN NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    CONSTRAINT uq_supplier_material_erp_id UNIQUE (erp_supplier_material_id),
    CONSTRAINT uq_supplier_material_contract UNIQUE (supplier_id, material_id, contract_id),
    CONSTRAINT fk_supplier_material_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id),
    CONSTRAINT fk_supplier_material_material FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT fk_supplier_material_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id),
    CONSTRAINT ck_supply_share_ratio CHECK (supply_share_ratio >= 0 AND supply_share_ratio <= 1),
    CONSTRAINT ck_supplier_material_approval
        CHECK (approved_status IN ('APPROVED', 'CONDITIONAL', 'PENDING', 'REJECTED')),
    CONSTRAINT ck_supplier_material_rank CHECK (priority_rank > 0),
    CONSTRAINT ck_supplier_material_period CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE TABLE inventory_snapshots (
    inventory_snapshot_id BIGSERIAL PRIMARY KEY,
    erp_inventory_snapshot_id VARCHAR(40) NOT NULL,
    material_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    on_hand_quantity NUMERIC(20, 4) NOT NULL,
    reserved_quantity NUMERIC(20, 4) NOT NULL,
    blocked_quantity NUMERIC(20, 4) NOT NULL,
    quality_hold_quantity NUMERIC(20, 4) NOT NULL,
    safety_stock_quantity NUMERIC(20, 4) NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    is_current BOOLEAN NOT NULL,
    source_unit VARCHAR(20) NOT NULL,
    normalized_unit VARCHAR(20) NOT NULL,
    data_quality_flag VARCHAR(30) NOT NULL,
    CONSTRAINT uq_inventory_snapshot_erp_id UNIQUE (erp_inventory_snapshot_id),
    CONSTRAINT fk_inventory_material FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (warehouse_id),
    CONSTRAINT ck_inventory_quantities CHECK (
        on_hand_quantity >= 0 AND reserved_quantity >= 0 AND blocked_quantity >= 0
        AND quality_hold_quantity >= 0 AND safety_stock_quantity >= 0
        AND on_hand_quantity >= reserved_quantity + blocked_quantity + quality_hold_quantity
    ),
    CONSTRAINT ck_inventory_quality CHECK (data_quality_flag IN ('VALID', 'STALE', 'INVALID'))
);

CREATE TABLE material_consumptions (
    consumption_id BIGSERIAL PRIMARY KEY,
    erp_consumption_id VARCHAR(40) NOT NULL,
    material_id BIGINT NOT NULL,
    plant_code VARCHAR(40) NOT NULL,
    average_daily_usage NUMERIC(20, 6),
    calculation_window_days INTEGER NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    is_current BOOLEAN NOT NULL,
    data_quality_flag VARCHAR(30) NOT NULL,
    CONSTRAINT uq_consumption_erp_id UNIQUE (erp_consumption_id),
    CONSTRAINT fk_consumption_material FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT ck_consumption_usage CHECK (average_daily_usage IS NULL OR average_daily_usage >= 0),
    CONSTRAINT ck_consumption_window CHECK (calculation_window_days > 0),
    CONSTRAINT ck_consumption_quality CHECK (data_quality_flag IN ('VALID', 'STALE', 'MISSING_USAGE', 'INVALID'))
);

CREATE TABLE purchase_orders (
    purchase_order_id BIGSERIAL PRIMARY KEY,
    erp_purchase_order_id VARCHAR(40) NOT NULL,
    po_number VARCHAR(60) NOT NULL,
    supplier_id BIGINT NOT NULL,
    order_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    order_status VARCHAR(30) NOT NULL,
    transport_mode VARCHAR(30) NOT NULL,
    port_of_entry VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_purchase_order_erp_id UNIQUE (erp_purchase_order_id),
    CONSTRAINT uq_purchase_order_number UNIQUE (po_number),
    CONSTRAINT fk_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id),
    CONSTRAINT ck_purchase_order_status
        CHECK (order_status IN ('OPEN', 'CONFIRMED', 'PARTIALLY_RECEIVED', 'DELAYED', 'CLOSED'))
);

CREATE TABLE purchase_order_items (
    purchase_order_item_id BIGSERIAL PRIMARY KEY,
    erp_purchase_order_item_id VARCHAR(40) NOT NULL,
    purchase_order_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    ordered_quantity NUMERIC(20, 4) NOT NULL,
    received_quantity NUMERIC(20, 4) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    expected_arrival_date DATE,
    confirmed_arrival_date DATE,
    unit_price NUMERIC(20, 6) NOT NULL,
    incoterm VARCHAR(20) NOT NULL,
    destination_warehouse_id BIGINT NOT NULL,
    CONSTRAINT uq_purchase_order_item_erp_id UNIQUE (erp_purchase_order_item_id),
    CONSTRAINT fk_purchase_order_item_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (purchase_order_id),
    CONSTRAINT fk_purchase_order_item_material FOREIGN KEY (material_id) REFERENCES materials (material_id),
    CONSTRAINT fk_purchase_order_item_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id),
    CONSTRAINT fk_purchase_order_item_warehouse FOREIGN KEY (destination_warehouse_id) REFERENCES warehouses (warehouse_id),
    CONSTRAINT ck_purchase_order_item_quantity
        CHECK (ordered_quantity >= 0 AND received_quantity >= 0 AND received_quantity <= ordered_quantity),
    CONSTRAINT ck_purchase_order_item_price CHECK (unit_price >= 0)
);

CREATE TABLE goods_receipts (
    goods_receipt_id BIGSERIAL PRIMARY KEY,
    erp_goods_receipt_id VARCHAR(40) NOT NULL,
    purchase_order_item_id BIGINT NOT NULL,
    received_quantity NUMERIC(20, 4) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    warehouse_id BIGINT NOT NULL,
    quality_status VARCHAR(30) NOT NULL,
    batch_number VARCHAR(80) NOT NULL,
    quality_released_at TIMESTAMPTZ,
    CONSTRAINT uq_goods_receipt_erp_id UNIQUE (erp_goods_receipt_id),
    CONSTRAINT fk_goods_receipt_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items (purchase_order_item_id),
    CONSTRAINT fk_goods_receipt_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (warehouse_id),
    CONSTRAINT ck_goods_receipt_quantity CHECK (received_quantity > 0),
    CONSTRAINT ck_goods_receipt_quality CHECK (quality_status IN ('ACCEPTED', 'QUALITY_HOLD', 'REJECTED'))
);

CREATE INDEX idx_contract_material ON contracts (material_id);
CREATE INDEX idx_supplier_material_lookup ON supplier_materials (material_id, supplier_id, valid_from, valid_to);
CREATE INDEX idx_inventory_current ON inventory_snapshots (material_id, is_current, snapshot_at DESC);
CREATE INDEX idx_consumption_current ON material_consumptions (material_id, is_current, calculated_at DESC);
CREATE INDEX idx_purchase_order_supplier_status ON purchase_orders (supplier_id, order_status);
CREATE INDEX idx_purchase_order_item_material ON purchase_order_items (material_id, expected_arrival_date, confirmed_arrival_date);
CREATE INDEX idx_goods_receipt_item ON goods_receipts (purchase_order_item_id);

COMMENT ON COLUMN materials.erp_material_id IS '외부 ERP 자재 식별자. 내부 PK material_id와 분리한다.';
COMMENT ON COLUMN suppliers.erp_supplier_id IS '외부 ERP 공급사 식별자. 내부 PK supplier_id와 분리한다.';
COMMENT ON COLUMN contracts.erp_contract_id IS '외부 ERP 계약 식별자. 내부 PK contract_id와 분리한다.';
