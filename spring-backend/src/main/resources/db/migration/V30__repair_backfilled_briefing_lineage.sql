-- V29 백필이 놓친 계보를 복구한다.
--
-- V29는 procurement_risk_assessments의 브리핑을 ai_briefings로 옮기면서
-- LEFT JOIN analyses ON a.analysis_id = p.analysis_id 으로 제목을 끌어왔다. 그런데
-- Chain A->B 자동 트리거 경로는 analysis_id를 <b>null로 보내고</b> news_id에 분석 UUID를
-- 텍스트로 넣는다 — AnalysisService.triggerMultiAgentBriefingSafely 주석대로, 그 시점엔
-- analysis가 아직 COMPLETED로 flush되지 않아 analysisId 경로를 타면 ANALYSIS_NOT_SCORED로
-- 막히기 때문이다.
--
-- 그래서 그 경로로 만들어진 행은 조인이 빗나가 COALESCE 폴백이 걸렸고, 화면 목록에
-- 기사 제목 대신 UUID가 그대로 노출됐다("1e6df4ec-2226-4f7e-95cc-59f969eafefd").
-- analysis_id도 비어 있어 "같은 뉴스면 새 것으로 교체"하는 중복 제거에도 걸리지 않는다.
--
-- news_id에 분석 UUID가 들어 있으므로 그걸로 다시 이어 붙인다.
UPDATE ai_briefings b
SET analysis_id     = a.analysis_id,
    subject_title   = a.event_title,
    -- source_headline은 외부신호로 쓴 기사 제목이다. 뉴스에서 출발한 브리핑은
    -- subject_title과 같은 값이 맞다(V29 백필도 같은 규칙이었다).
    source_headline = COALESCE(b.source_headline, a.event_title)
FROM analyses a
WHERE b.analysis_id IS NULL
  AND b.source_type = 'NEWS'
  -- news_id가 UUID 형태일 때만. 다른 형식(외부 뉴스 식별자 등)은 건드리지 않는다.
  AND b.news_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  AND a.analysis_id = b.news_id::UUID;

-- 위 복구로 analysis_id가 채워지면서 같은 뉴스의 브리핑이 여러 건 남아 있을 수 있다
-- (중복 제거는 저장 시점에만 도므로 과거 데이터에는 적용된 적이 없다). 뉴스당 최신 1건만
-- 남긴다 — AiBriefingRepository.replaceSameNewsBriefing과 같은 규칙이다.
DELETE FROM ai_briefings b
WHERE b.source_type = 'NEWS'
  AND b.analysis_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM ai_briefings newer
      WHERE newer.source_type = 'NEWS'
        AND newer.analysis_id = b.analysis_id
        AND (newer.created_at, newer.briefing_id) > (b.created_at, b.briefing_id)
  );
