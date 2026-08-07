ALTER TABLE supplier_materials
    ADD COLUMN available_capacity_quantity NUMERIC(20, 4);

COMMENT ON COLUMN supplier_materials.available_capacity_quantity IS
    '대체 공급사의 즉시 투입 가능 생산능력 수량. 확인되지 않으면 NULL(UNKNOWN).';
