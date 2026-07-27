-- F7 근거 계보(Phase 1): 브리핑이 근거로 삼은 Severity 판정을 ID로 연결한다.
-- 기존 브리핑 행에는 값이 없으므로 NULL을 허용한다(옛 브리핑은 링크 이전이라 NULL이 정직한 표현).
ALTER TABLE briefings
    ADD COLUMN assessment_id UUID;

ALTER TABLE briefings
    ADD CONSTRAINT fk_briefing_assessment
        FOREIGN KEY (assessment_id) REFERENCES severity_assessments (assessment_id);

CREATE INDEX idx_briefing_assessment
    ON briefings (assessment_id);

COMMENT ON COLUMN briefings.assessment_id IS
    'F7 근거 계보: 이 브리핑이 근거로 삼은 severity_assessments 행. V8 이전 브리핑은 NULL';
