-- 구매 리스크 평가 "완료 처리"(acknowledge) 로그.
--
-- procurement_risk_assessments(V18)는 append-only 감사추적 테이블이라 기존 행에
-- resolved_at 같은 컬럼을 얹어 UPDATE하지 않는다. notification_log(V12)와 같은 구조 —
-- 별도 append-only 로그 테이블에 "누가 언제 이 평가를 처리했다"만 남긴다.
--
-- 대시보드 KPI 집계(DashboardRepository)가 "카테고리별 최신 1건"을 고를 때 여기 있는
-- assessment_id는 건너뛴다. 그 평가가 그 카테고리의 유일한/최신 평가였다면 카테고리가
-- 자연스럽게 집계에서 빠지고, 이후 그 카테고리에 새 평가(새 assessment_id)가 들어오면
-- 이 로그에 없으므로 자동으로 다시 집계에 잡힌다 — 별도 로직 없이 이 구조만으로 보장된다.
CREATE TABLE procurement_risk_acknowledgements (
    acknowledgement_id  UUID PRIMARY KEY,
    assessment_id       UUID NOT NULL REFERENCES procurement_risk_assessments(assessment_id),
    acknowledged_by     BIGINT NOT NULL REFERENCES users(id),
    acknowledged_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 같은 평가를 두 번 완료 처리해도 중복 행이 안 쌓이게(멱등성) — ON CONFLICT DO NOTHING의 대상.
    CONSTRAINT uq_pra_assessment UNIQUE (assessment_id)
);

CREATE INDEX idx_pra_ack_assessment ON procurement_risk_acknowledgements (assessment_id);

COMMENT ON TABLE procurement_risk_acknowledgements IS
    '구매 리스크 평가 완료 처리 로그(append-only) — procurement_risk_assessments는 불변으로 유지';
