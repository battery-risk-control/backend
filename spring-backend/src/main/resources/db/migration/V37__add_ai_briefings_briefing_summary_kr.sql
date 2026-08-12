-- 경영기획 AI 브리핑 상세 화면 전용 '자세한 뉴스 요약'.
--
-- 추출 단계의 analyses.summary_kr(2문장 이내)은 너무 짧아, 상세 화면에서 배경·원인·공급망
-- 영향까지 담은 더 자세한 요약을 따로 보여주고 싶다는 요구가 있었다. FastAPI
-- /internal/llm/summarize로 새로 생성한 요약을 여기에 캐시한다(브리핑당 1회 생성 후 재사용).
-- NULL이면 아직 생성 전 — 화면은 summary_kr로 폴백한다.
ALTER TABLE ai_briefings ADD COLUMN briefing_summary_kr TEXT;

COMMENT ON COLUMN ai_briefings.briefing_summary_kr IS
    '경영기획 상세용 LLM 자세한 요약(추출 summary_kr보다 길다). NULL이면 미생성';
