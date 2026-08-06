-- F4/F3: raw_events에만 있던 원문 URL을 analyses에도 저장해, 분석 결과 조회·알림에서
-- 원문 기사로 바로 연결할 수 있게 한다.
ALTER TABLE analyses ADD COLUMN source_url VARCHAR(500);
