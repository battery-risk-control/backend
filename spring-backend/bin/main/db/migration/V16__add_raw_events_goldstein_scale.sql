-- F4/F3: GDELT가 이벤트 자체에 이미 계산해준 GoldsteinScale을 raw_events에 원본 그대로 보존해,
-- F3 분석에서 국가 단위 재조회(부정확할 수 있음) 대신 이 값을 우선 사용할 수 있게 한다.
ALTER TABLE raw_events ADD COLUMN goldstein_scale DOUBLE PRECISION;
