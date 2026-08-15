-- 시연용 마스터 계정(master@test.local)을 운영 DB에 시드한다.
--
-- 배경: 운영의 테스트 계정들은 코드 시드(AuthTestSeedConfig)가 아니라 수동으로 만들어져 있고,
-- 운영에선 AUTH_TEST_SEED_ENABLED가 꺼져 있어 마스터가 생성되지 않는다. AUTH_TEST_SEED_ENABLED를
-- 켜면 수동 계정과 이메일이 충돌해 시드가 예외를 내고 백엔드가 죽을 위험이 있어, 대신 이 마이그레이션으로
-- 마스터만 안전하게 추가한다(배포 시 Flyway가 자동 실행 → 담당자 개입 불필요).
--
-- 설계:
--  * 비밀번호 해시를 저장소에 박지 않으려고, 이미 있는 구매팀 테스트 계정
--    (email=purchasing@test.local, 비번 test1234!)의 password·org_name을 그대로 복사한다.
--    → 마스터도 test1234!로 로그인되고, git에는 해시가 남지 않는다.
--  * role='MASTER'는 V39가 이미 CHECK 제약에 추가해 통과한다.
--  * NOT EXISTS 가드로 전 환경에서 안전하게 동작한다:
--     - 운영: purchasing 있음 + master 없음 → 삽입
--     - 로컬/CI 초기 기동: 이 시점엔 purchasing이 없어(Flyway가 AuthTestSeedConfig보다 먼저 실행)
--       무삽입 → 이후 test-seed가 마스터를 만든다
--     - 마스터가 이미 있는 로컬: 무삽입 → 이메일 유니크 충돌·크래시 없음
--  * email을 정확히 'purchasing@test.local'로 지목해, 실제 구매팀 사용자(다른 비밀번호)의 해시를
--    잘못 복사하지 않게 한다.
--
-- 제거하려면: DELETE FROM users WHERE username = 'master@test.local'; (또는 후속 마이그레이션)

INSERT INTO users (
    username, password, name, email, org_name,
    role, approval_status, enabled, created_at, updated_at
)
SELECT
    'master@test.local', password, '마스터', 'master@test.local', org_name,
    'MASTER', 'APPROVED', TRUE, now(), now()
FROM users
WHERE email = 'purchasing@test.local'
  AND NOT EXISTS (
      SELECT 1 FROM users WHERE username = 'master@test.local' OR email = 'master@test.local'
  )
LIMIT 1;
