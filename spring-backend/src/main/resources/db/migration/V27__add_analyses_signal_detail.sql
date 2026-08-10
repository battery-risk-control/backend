-- 구매팀 리스크 모니터링 "이벤트 상세"의 뉴스 요약·외부신호 패널용 컬럼.
--
-- 네 값 모두 이미 F3 파이프라인이 계산하지만 지금까지 어디에도 남지 않았다.
-- FastAPI /analyze 응답에는 extraction(summary_kr·tone_score)과 features(goldstein_scale·
-- news_count·tone_score)가 들어 있는데 Spring이 classification·severity만 읽고 버렸다.
-- 화면이 "왜 이 점수가 나왔는가"를 보여주려면 점수의 입력값이 함께 남아 있어야 한다.
--
-- severity_score와 같은 행에 두는 이유: 외부신호 패널은 "이 점수를 만든 입력값"을 보여주는
-- 자리다. 다른 테이블로 나누면 재계산·재분석으로 점수만 바뀌었을 때 입력값이 옛것으로 남아
-- 화면에서 점수와 근거가 어긋난다.
ALTER TABLE analyses ADD COLUMN summary_kr TEXT;
ALTER TABLE analyses ADD COLUMN tone_score DOUBLE PRECISION;
ALTER TABLE analyses ADD COLUMN news_count INTEGER;
ALTER TABLE analyses ADD COLUMN goldstein_scale DOUBLE PRECISION;

COMMENT ON COLUMN analyses.summary_kr IS 'LLM 추출이 만든 한국어 요약. NULL이면 미추출(화면은 요약 블록을 숨김)';
COMMENT ON COLUMN analyses.tone_score IS 'GDELT AvgTone 또는 LLM 추출 tone. 음수일수록 부정적';
COMMENT ON COLUMN analyses.news_count IS '같은 국가·같은 날 수집된 관련 뉴스 건수(severity 입력값)';
COMMENT ON COLUMN analyses.goldstein_scale IS 'severity 계산에 실제로 쓰인 Goldstein 값(-10~+10). raw_events의 원본과 달리 병합·기본값 적용 후 값';
