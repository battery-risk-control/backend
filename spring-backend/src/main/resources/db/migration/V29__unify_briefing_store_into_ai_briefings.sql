-- 브리핑 본문 저장소를 ai_briefings 하나로 통일한다.
--
-- 어쩌다 둘이 됐나:
--   V23(ai_briefings)이 먼저 들어왔고, 그 주석에 "점수 이력과 본문은 입자가 다르니 분리하고
--   assessment_id로 잇는다"고 설계를 적어뒀다. 그 뒤 팀 저장소 #13을 가져오면서 V24가
--   같은 문제를 다른 방식으로 풀어 procurement_risk_assessments에 본문 4컬럼을 붙였다.
--   상류에는 ai_briefings가 없어서 그쪽에선 그게 유일한 해법이었다 — 여기서만 충돌한다.
--
-- 그래서 실제로 벌어진 일:
--   AiBriefingService가 멀티에이전트를 돌리면 MultiAgentOrchestrationService가 본문을
--   procurement_risk_assessments에 쓰고, 이어서 AiBriefingService가 같은 본문을
--   ai_briefings에 또 썼다(중복 8건). 반대로 자동 경로(Chain A->B)와 리스크 모니터링
--   ERP·계약 영향 분석은 ai_briefings에 쓰지 않아, 브리핑이 생성됐는데도 AI 브리핑 화면
--   목록에 나오지 않았다(고아 8건).
--
-- 왜 V24를 고치지 않고 되돌리나:
--   이미 적용된 마이그레이션을 편집하면 Flyway 체크섬 검증에서 걸려 기존 DB가 전부 기동
--   불가가 된다. 되돌리는 안전한 방법은 새 마이그레이션을 얹는 것뿐이다. V24가 빈 DB에서
--   "만들었다가 지우는" 왕복이 되는 건 감수한다 — 마이그레이션 이력은 현재 스키마의 정의가
--   아니라 변경의 기록이기 때문이다.
--
-- 상류로 올릴 때:
--   팀 저장소는 ai_briefings 없이 V19(=이 저장소의 V24) 컬럼을 계속 쓴다. 통합 시점에
--   "본문 정본을 ai_briefings로 옮긴다"를 합의해야 하고, 그때 V19~V29 구간 재번호도 같이
--   정리해야 한다. 이 주석이 그 근거다.

-- 1) 자동 생성분과 화면 생성분이 한 목록에 공존해야 하므로 구분자를 둔다.
--    기존 행은 전부 AI 브리핑 화면에서 만든 것이라 MANUAL이 맞다.
ALTER TABLE ai_briefings
    ADD COLUMN trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE ai_briefings
    ADD CONSTRAINT ck_ai_briefing_trigger
        CHECK (trigger_type IN ('AUTO', 'MANUAL'));

COMMENT ON COLUMN ai_briefings.trigger_type IS
    'AUTO=분석 파이프라인이 자동으로 돌려 만든 브리핑, MANUAL=화면에서 사용자가 생성 버튼을 눌러 만든 브리핑';

-- 2) procurement_risk_assessments에만 있던 본문을 옮긴다.
--    ai_briefings에 이미 같은 assessment_id 행이 있으면 그건 화면이 저장한 정본이므로 건너뛴다
--    (중복 8건 — 옮기면 목록에 같은 브리핑이 두 번 나온다).
INSERT INTO ai_briefings (
    briefing_id, assessment_id, source_type, source_ref, subject_title, news_id,
    analysis_id, source_headline, erp_material_id, erp_supplier_id, material_category,
    impact_domain_final, external_signal_level, external_signal_score,
    erp_exposure_level, erp_exposure_score, contract_gap_score, contract_clause_count,
    procurement_risk_level, procurement_risk_score, composite,
    briefing_text, risk_reasons, recommended_actions, contract_findings, warnings,
    review_passed, llm_used, weight_version, mock, trigger_type, created_by, created_at
)
SELECT
    gen_random_uuid(),
    p.assessment_id,
    -- 옮겨오는 건 전부 뉴스 이벤트에서 출발한 실행이다. 자재/계약 화면에서 시작한 브리핑은
    -- 애초에 AiBriefingService를 거쳐 ai_briefings에 이미 들어가 있다.
    'NEWS',
    COALESCE(a.analysis_id::TEXT, p.news_id),
    -- subject_title은 NOT NULL이다. analyses가 지워졌거나 analysis_id가 없던 실행은
    -- news_id로라도 채운다 — 목록에서 빈 줄로 보이는 것보다 식별자라도 보이는 게 낫다.
    COALESCE(a.event_title, p.news_id),
    p.news_id,
    p.analysis_id,
    a.event_title,
    p.erp_material_id,
    p.erp_supplier_id,
    p.material_category,
    p.impact_domain_final,
    p.external_signal_level,
    p.external_signal_score,
    -- erp_exposure_level은 점수 테이블에 없다(응답 Map 안에만 있었다). 점수에서 역산하지
    -- 않고 NULL로 둔다 — 등급 경계는 risk_node가 정하는 값이라 여기서 추측하면 틀린다.
    NULL,
    p.erp_exposure_score,
    p.contract_gap_score,
    jsonb_array_length(COALESCE(p.contract_findings, '[]'::JSONB)),
    p.procurement_risk_level,
    p.procurement_risk_score,
    -- ERP 노드까지 실제로 돌았는지. AiBriefingService·RiskMonitoringService·ContractRagService가
    -- 쓰는 판별 기준과 같다(조기 종료 경로는 erp_assessment를 빈 dict로 두므로 점수가 없다).
    p.erp_exposure_score IS NOT NULL,
    p.briefing,
    p.risk_reasons,
    COALESCE(p.recommended_actions, '[]'::JSONB),
    COALESCE(p.contract_findings, '[]'::JSONB),
    COALESCE(p.warnings, '[]'::JSONB),
    p.review_passed,
    p.llm_used,
    p.weight_version,
    p.mock,
    'AUTO',
    -- 사람이 누른 게 아니므로 created_by는 비운다. 화면 생성분과 구분되는 또 하나의 표식이다.
    NULL,
    p.created_at
FROM procurement_risk_assessments p
LEFT JOIN analyses a ON a.analysis_id = p.analysis_id
WHERE p.briefing IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ai_briefings b WHERE b.assessment_id = p.assessment_id
  );

-- 3) 본문은 이제 ai_briefings에만 있다. procurement_risk_assessments는 V18 원래 목적대로
--    점수 이력만 남긴다.
ALTER TABLE procurement_risk_assessments
    DROP COLUMN briefing,
    DROP COLUMN recommended_actions,
    DROP COLUMN contract_findings,
    DROP COLUMN warnings;
