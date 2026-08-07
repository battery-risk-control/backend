-- 원자재 가격 추이(공개 대시보드 패널). 자재 대분류별 대표 종목의 일별 종가를 보관한다.
--
-- 현물가가 아니라 "대표 채굴/생산 기업의 주가"를 프록시로 쓴다 — 배터리 핵심광물은 공개 현물
-- 시세 API가 없거나 유료라, 학습 데이터 수집 단계부터 yfinance 상장 종목을 대리 지표로 삼아왔다
-- (data/yfinance/collect_yfinance_data.py의 MATERIAL_TICKER_MAP과 같은 매핑).
--
-- (material_category, price_date)를 PK로 두어 스케줄러가 같은 구간을 다시 받아도 upsert로
-- 수렴하게 한다 — 며칠 꺼져 있다가 재기동해도 빠진 구간이 자동으로 메워진다.
CREATE TABLE material_price_points (
    material_category VARCHAR(40)     NOT NULL,
    price_date        DATE            NOT NULL,
    close_price       DOUBLE PRECISION NOT NULL,
    -- 20일 종가 변동성(일간 표준편차). 요약 카드의 리스크 지수 산출에 쓰며, 데이터가 20일치
    -- 미만인 구간에서는 NULL이 될 수 있다.
    stock_vol_20d     DOUBLE PRECISION,
    ticker            VARCHAR(20),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_material_price_points PRIMARY KEY (material_category, price_date)
);

-- 공개 패널은 "최근 N일"만 조회하므로 날짜 역순 스캔을 돕는다.
CREATE INDEX idx_material_price_points_date ON material_price_points (price_date DESC);
