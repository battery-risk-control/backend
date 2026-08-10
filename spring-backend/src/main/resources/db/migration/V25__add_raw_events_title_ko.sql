-- 공개 뉴스 마퀴·목록용 한국어 제목.
--
-- GDELT는 영문 기사만 수집하는데(sourcelang:english) 화면은 한국어라, 원문 제목을 그대로
-- 띄우면 비로그인 방문자가 읽을 수 없다. 번역본을 원본 옆에 저장해 두고 조회 시 골라 쓴다.
--
-- analyses가 아니라 raw_events에 두는 이유: 뉴스 패널은 분석(F3)이 돌지 않아도 채워져야 한다.
-- analysis-enabled=false로 두고 수집만 하는 운영 모드가 기본값이라, 번역을 analyses에 걸면
-- 기본 상태에서 영영 번역되지 않는다.
ALTER TABLE raw_events ADD COLUMN title_ko VARCHAR(500);

-- 번역 시도 횟수. 번역은 수집과 분리된 스케줄러가 "title_ko가 비어 있는 행"을 집어가는데,
-- 특정 행이 계속 실패하면(제목이 깨졌거나 LLM이 매번 거부) 매 주기 같은 행을 다시 집어
-- 예산만 태우고 뒤 기사는 영영 순서가 오지 않는다. 몇 번 실패하면 포기하고 넘어간다.
ALTER TABLE raw_events ADD COLUMN translation_attempts SMALLINT NOT NULL DEFAULT 0;

-- 번역 대상 조회 전용 부분 인덱스. 대상은 "제목이 있는 뉴스 중 아직 번역 안 된 것"뿐이라
-- 전체 raw_events를 스캔하지 않게 한다.
CREATE INDEX idx_raw_events_untranslated
    ON raw_events (collected_at DESC)
    WHERE data_type = 'NEWS' AND title IS NOT NULL AND title_ko IS NULL;

COMMENT ON COLUMN raw_events.title_ko IS '한국어로 번역된 제목. NULL이면 미번역(화면은 원문 표시)';
