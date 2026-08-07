-- F10: 메일 알림을 최종 합성 위험도(procurement_risk_assessments) 기준으로 전환하면서
-- 중복 발송 방지 키를 analysis_id → assessment_id로 바꾼다.
--
-- 왜 바꾸나:
--  1) analysis_id는 요청 본문으로 외부신호를 직접 넣은 합성 평가에서 NULL이 될 수 있다. Postgres
--     UNIQUE는 NULL을 서로 다르게 취급하므로, analysis_id 키로는 그런 평가의 중복 발송이 막히지 않는다.
--  2) 한 analysis가 자재별로 여러 assessment를 낳는다(procurement_risk_assessments grain =
--     뉴스이벤트 × 자재). analysis_id 키에는 자재 차원이 없어, 자재별로 메일을 보내면 첫 자재만
--     남고 나머지가 중복으로 눌린다.
-- assessment_id는 항상 생성되고(NULL 없음) 자재별로 고유하므로 두 문제를 함께 해결한다.

ALTER TABLE notification_log ADD COLUMN assessment_id UUID;

-- 과거 행은 analyses 기반이라 assessment_id가 없다. NULL로 남기며, UNIQUE에서 NULL은 서로 다르게
-- 취급되므로 과거 행끼리도, 과거 행과 신규 행 사이에도 충돌하지 않는다.

ALTER TABLE notification_log DROP CONSTRAINT uq_notification_analysis_channel_recipient;

-- 합성 평가는 analysis_id가 NULL일 수 있으므로 NOT NULL을 푼다(추적용 컬럼으로만 유지).
ALTER TABLE notification_log ALTER COLUMN analysis_id DROP NOT NULL;

ALTER TABLE notification_log
    ADD CONSTRAINT uq_notification_assessment_channel_recipient UNIQUE (assessment_id, channel, recipient);

CREATE INDEX idx_notification_log_assessment ON notification_log (assessment_id);

COMMENT ON COLUMN notification_log.assessment_id IS
    'F10 발송·중복방지 키. procurement_risk_assessments.assessment_id. 과거 analyses 기반 행은 NULL.';
COMMENT ON COLUMN notification_log.analysis_id IS
    'F10 추적용(합성 평가의 출처 분석). 요청 본문으로 외부신호를 직접 넣은 평가는 NULL 가능.';
