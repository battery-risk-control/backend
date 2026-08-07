-- 환율(공개 대시보드 ② 뉴스 마퀴 옆 밴드). 한국수출입은행 현재환율 API의 일별 고시환율을 보관한다.
--
-- 원천 API(https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON)는 "일환율"이라
-- 영업일 11시 전후로 하루 한 번만 갱신되고, 비영업일이나 영업당일 11시 이전 요청에는 빈 응답이
-- 돌아온다. 화면이 그때마다 비지 않게 하려면 받은 날짜를 그대로 쌓아두고 조회는 "가장 최근 고시일"을
-- 보는 수밖에 없다 — 그래서 캐시가 아니라 테이블이다.
--
-- 전일 대비 등락도 이 테이블 없이는 계산할 수 없다. 원천 API는 특정 하루치만 주고 변동분을 주지 않아,
-- 직전 고시일 행이 우리 쪽에 남아 있어야 비교가 성립한다.
--
-- 저장 범위는 화면에 띄우는 통화가 아니라 "거래 가능한 전 통화"(약 21종)다. 원천이 한 번의
-- 호출로 전 통화를 주므로 골라 담아도 호출 비용이 같은데, 버리면 나중에 노출 통화를 늘릴 때
-- 과거 이력이 없어 전일 대비가 한동안 비기 때문이다. 노출 대상은 조회 시점에 고른다
-- (app.exchange-rate.currencies). 하루 약 21행이라 1년 쌓아도 8천 행 수준이다.
--
-- (currency_code, rate_date)를 PK로 두어 같은 날짜를 다시 받아도 upsert로 수렴한다 —
-- 스케줄러가 하루 두 번 돌고 기동 시 backfill도 하지만 중복 행이 생기지 않는다.
CREATE TABLE exchange_rate_points (
    -- 원문 cur_unit에서 단위 배수를 떼어낸 통화코드. "JPY(100)" -> "JPY", "USD" -> "USD".
    currency_code   VARCHAR(10)      NOT NULL,
    -- 고시일. 요청한 날짜가 아니라 "데이터가 실제로 있던 날짜"를 넣는다.
    rate_date       DATE             NOT NULL,
    -- 원문 cur_nm. "미국 달러", "위안화" 등 API가 주는 한글 표기를 그대로 쓴다.
    currency_name   VARCHAR(60)      NOT NULL,
    -- 원문 cur_unit의 괄호 표기. JPY/IDR은 100단위 고시라 100, 나머지는 1이다.
    -- 아래 rate 값들은 이 배수 기준의 원문 그대로다(100엔당 951.05원 -> 951.05).
    -- 1단위로 환산해 저장하지 않는 이유: 환율은 관례상 "100엔 = 951.05원"으로 표기하므로
    -- 원문을 보존해야 화면 표기와 어긋나지 않는다.
    unit_multiplier INT              NOT NULL DEFAULT 1,
    -- 매매 기준율(deal_bas_r). 화면에 띄우는 대표값이다.
    deal_base_rate  DOUBLE PRECISION NOT NULL,
    -- 전신환 받으실때(ttb) / 보내실때(tts). 지금 화면은 매매기준율만 쓰지만, 같은 호출로 딸려오는
    -- 값이라 버릴 이유가 없다. 나중에 상세 패널이 생기면 추가 호출 없이 바로 쓴다.
    ttb             DOUBLE PRECISION,
    tts             DOUBLE PRECISION,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_exchange_rate_points PRIMARY KEY (currency_code, rate_date)
);

-- 공개 패널은 "가장 최근 고시일 + 직전 고시일"만 보므로 날짜 역순 스캔을 돕는다.
CREATE INDEX idx_exchange_rate_points_date ON exchange_rate_points (rate_date DESC);
