-- V31의 나머지 절반. 같은 reviewer 오판정이 두 테이블에 남았는데 한쪽만 고쳤다.
--
-- review_passed는 ai_briefings와 procurement_risk_assessments 양쪽에 저장된다(같은 실행의
-- 판정을 본문 저장소와 점수 이력이 각각 들고 있다). V31은 ai_briefings만 고쳤는데, 대시보드
-- KPI의 verified_briefing_count는 procurement_risk_assessments를 읽는다
-- (DashboardRepository.loadProcurementRiskSummary의 latest CTE) — 그래서 화면 숫자는 아직
-- 복구되지 않은 상태였다.
--
-- 판별을 ai_briefings로 하는 이유:
--   V29에서 procurement_risk_assessments.warnings를 ai_briefings로 옮겼으므로, 이 테이블만
--   봐서는 어떤 경고 때문에 false가 됐는지 알 수 없다. 같은 실행의 브리핑이 그 경고를 갖고
--   있으므로 assessment_id로 이어 붙여 판단한다.
--
-- 브리핑이 없는 행은 건드리지 않는다. 실측 9건 중 2건이 그렇다 — 판정 근거를 확인할 방법이
-- 없는데 통과로 바꾸면 그건 복구가 아니라 추측이다. NOT NULL 조인이 자연스럽게 걸러낸다.
--
-- 경고 판별 기준은 V31과 같아야 한다. 두 마이그레이션이 서로 다른 기준을 쓰면 같은 실행의
-- 판정이 테이블마다 갈린다.

UPDATE procurement_risk_assessments p
SET review_passed = TRUE
FROM ai_briefings b
WHERE b.assessment_id = p.assessment_id
  AND p.review_passed IS NOT TRUE
  AND jsonb_array_length(b.warnings) > 0
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements_text(b.warnings) AS w(text)
      WHERE w.text NOT LIKE 'KG 게이트를 우회한 실행입니다%'
        AND w.text NOT LIKE '브리핑을 OpenAI로 생성했습니다%'
  );
