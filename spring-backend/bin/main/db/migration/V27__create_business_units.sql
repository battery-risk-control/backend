-- 2계층(경영기획팀, 코드상 STRATEGY) 대시보드가 요구하는 "사업부(business_unit)" 개념.
-- 지금까지 이 개념은 프론트엔드에만 있었다 — materials/materials_category 어디에도
-- 사업부 컬럼이 없어, 프론트가 자재 3종(니켈/리튬/코발트)만 임시로 하드코딩해 썼다.
--
-- material_category(8종, RiskEventService.MATERIAL_NAME_KO와 동일한 키)를 기준으로
-- 5:3 분류한다 — 배터리셀사업부(활물질 5종)/소재부품사업부(구조재 3종). 사용자 승인
-- (2026-08-02 계획 수립 세션)에 따른 분류다.
CREATE TABLE business_units (
    business_unit_id VARCHAR(20) PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO business_units (business_unit_id, name) VALUES
    ('BU-CELL', '배터리셀사업부'),
    ('BU-MATERIAL', '소재부품사업부');

ALTER TABLE materials
    ADD COLUMN business_unit_id VARCHAR(20) REFERENCES business_units (business_unit_id);

-- material_category 기준 백필은 여기서 하지 않는다 — 이 마이그레이션이 실행되는 시점엔
-- materials 테이블이 비어 있다(ERP CSV 시드는 ApplicationRunner로 애플리케이션 기동 후에
-- 실행되므로 마이그레이션보다 항상 늦다). 실제 백필은 ErpSeedConfig가 자재 upsert 직후
-- 매 시드 실행마다 수행한다(재시드해도 항상 최신 상태로 유지됨).

CREATE INDEX idx_materials_business_unit ON materials (business_unit_id);

-- material_category만 갖고 있는 테이블(procurement_risk_assessments, analyses)이
-- material_id 없이도 사업부를 조인할 수 있게 하는 룩업. materials 중복을 피하려고
-- DISTINCT 뷰로 만든다 — 같은 category의 모든 material이 항상 같은 business_unit_id를
-- 갖도록 위에서 이미 백필했으므로 category 1개당 정확히 1행만 나온다.
CREATE VIEW material_category_business_units AS
    SELECT DISTINCT material_category, business_unit_id
    FROM materials
    WHERE material_category IS NOT NULL AND business_unit_id IS NOT NULL;
