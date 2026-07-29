-- [surin 병합·화해] contract_documents.document_type CHECK를 merge/surin 두 taxonomy의 합집합으로 확장(무손실).
-- 주의: V1의 실제 테이블명은 documents가 아니라 contract_documents.
-- merge V1: CONTRACT/PURCHASE_ORDER/SPECIFICATION/CERTIFICATE/OTHER
-- surin V1: LTA/PURCHASE_GUIDELINE/SUPPLIER_EVALUATION/QUALITY_CERTIFICATE/REGULATION/TECHNICAL_SPEC
ALTER TABLE contract_documents DROP CONSTRAINT ck_document_type;
ALTER TABLE contract_documents ADD CONSTRAINT ck_document_type CHECK (document_type IN (
    'CONTRACT', 'PURCHASE_ORDER', 'SPECIFICATION', 'CERTIFICATE', 'OTHER',
    'LTA', 'PURCHASE_GUIDELINE', 'SUPPLIER_EVALUATION', 'QUALITY_CERTIFICATE', 'REGULATION', 'TECHNICAL_SPEC'
));
