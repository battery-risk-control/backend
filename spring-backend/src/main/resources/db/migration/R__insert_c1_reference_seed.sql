INSERT INTO materials (
    material_code, material_name, material_name_en, material_grade, base_unit
) VALUES (
    'C1-NICKEL', '니켈', 'Nickel', 'BATTERY_GRADE', 'KG'
)
ON CONFLICT (material_code) DO NOTHING;

INSERT INTO suppliers (
    supplier_code, supplier_name, business_registration_number, country_code,
    feoc_status, iatf_16949_certified, ppap_approved
) VALUES (
    'C1-SUPPLIER-ID', 'C1 인도네시아 테스트 공급사', 'C1-SEED-0001', 'ID',
    'UNKNOWN', TRUE, TRUE
)
ON CONFLICT (supplier_code) DO NOTHING;

INSERT INTO contracts (
    contract_number, supplier_id, contract_name, start_date, end_date,
    currency_code, status
)
SELECT
    'C1-CONTRACT-001', supplier_id, 'C1 니켈 장기공급계약',
    DATE '2026-01-01', DATE '2026-12-31', 'USD', 'ACTIVE'
FROM suppliers
WHERE supplier_code = 'C1-SUPPLIER-ID'
ON CONFLICT (contract_number) DO NOTHING;
