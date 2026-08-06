-- 프론트엔드 인증 계약(email 로그인 + 관리자 승인 대기)을 지원하기 위한 users 확장.
-- 기존 username 기반 로그인과 기존 계정을 그대로 유지하기 위해 모두 추가(additive) 컬럼으로 둔다.

ALTER TABLE users
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN org_name VARCHAR(150),
    ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';

-- 이메일은 있을 때만 유일해야 하므로 NULL 다중 허용(PostgreSQL 기본 동작)을 그대로 사용한다.
ALTER TABLE users
    ADD CONSTRAINT uk_users_email UNIQUE (email),
    ADD CONSTRAINT ck_users_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));

CREATE INDEX idx_users_approval_status ON users (approval_status);

COMMENT ON COLUMN users.email IS '프론트엔드 로그인 식별자. 기존 username 로그인과 병행 지원한다.';
COMMENT ON COLUMN users.approval_status IS
    '관리자 승인 상태. 기존 계정과 username 기반 가입은 APPROVED, 프론트엔드 가입은 PENDING으로 시작한다.';
