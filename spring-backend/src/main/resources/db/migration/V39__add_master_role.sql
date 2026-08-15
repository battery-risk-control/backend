-- 시연용 마스터(MASTER) 역할 신설. 1·2·3계층(구매/기획/임원) 대시보드를 한 계정으로 모두
-- 열람할 수 있는 데모 전용 역할이다. 실제 부여는 test-seed(AuthTestSeedConfig)에서만 하며,
-- 운영 시드(AdminSeedConfig)나 회원가입으로는 만들 수 없다.
-- V36에서 재정의한 ck_users_role 제약을 DROP 후 재생성한다(기존 데이터는 4종뿐이라 재검증 통과).

ALTER TABLE users DROP CONSTRAINT ck_users_role;

ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('PURCHASING', 'STRATEGY', 'EXECUTIVE', 'ADMIN', 'MASTER'));
