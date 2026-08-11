-- "데이터 품질" 탭의 ERP 상태 표시용. 현재 아키텍처에는 주기적 ERP 동기화가 없다 —
-- ERP 적재는 컨테이너 시작 시 1회 CSV 시드(ErpSeedConfig)뿐이다. 이 테이블은 그 사실을
-- 정직하게 기록한다("동기화 주기"가 아니라 "마지막 적재 시각").
CREATE TABLE pipeline_health_log (
    component    VARCHAR(50) PRIMARY KEY,
    status       VARCHAR(20) NOT NULL,
    last_run_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_pipeline_health_status CHECK (status IN ('OK', 'FAILED'))
);
