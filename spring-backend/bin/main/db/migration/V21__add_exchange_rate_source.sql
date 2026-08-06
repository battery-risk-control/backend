-- 재정환율(cross rate) 도입. 환율 한 행이 "직접 고시"인지 "USD를 경유해 계산한 값"인지 구분한다.
--
-- 배경: 수출입은행 API는 국내 은행권이 매매하는 21종만 고시한다. 칠레(CLP)·아르헨티나(ARS)·
-- 콩고민주공화국(CDF)·남아공(ZAR)·브라질(BRL)·필리핀(PHP)은 우리 핵심 조달국인데도 빠져 있어,
-- USD를 경유해 계산한다:
--
--     CLP/KRW = (수출입은행 USD/KRW) / (외부소스 USD/CLP)
--
-- KRW 다리를 수출입은행 고시로 고정하는 것이 핵심이다. 외부소스의 KRW를 쓰면 같은 화면 안에서
-- 직접 고시 USD/KRW와 재정환율의 기준이 갈라져 서로 안 맞는다.
--
-- 이 구분을 컬럼으로 남기는 이유: 재정환율은 두 소스의 스냅샷 시각이 섞여 있어 엄밀히는 어느
-- 한 시점의 환율도 아니다. 화면 표시용으로는 충분하지만 정산·회계 근거로는 쓸 수 없으므로,
-- 나중에 누가 이 값을 집계에 끌어다 쓰기 전에 출처가 드러나 있어야 한다.
--
-- 오차 규모: 무료 소스 두 곳(open.er-api·ECB)을 비교했을 때 USD/KRW가 0.29%, 통화별 재정환율이
-- 중앙값 0.14%(최대 1.18%) 어긋났다. 서로 다른 소스는 항상 이 정도로 어긋난다는 참고치이며,
-- 수출입은행 대비 실측치는 아니다 — 그건 인증키를 발급받아야 확인할 수 있다.
ALTER TABLE exchange_rate_points
    ADD COLUMN rate_source VARCHAR(20) NOT NULL DEFAULT 'KOREAEXIM';

-- KOREAEXIM  수출입은행 직접 고시
-- CROSS_USD  USD를 경유한 재정환율
ALTER TABLE exchange_rate_points
    ADD CONSTRAINT ck_exchange_rate_points_source
        CHECK (rate_source IN ('KOREAEXIM', 'CROSS_USD'));
