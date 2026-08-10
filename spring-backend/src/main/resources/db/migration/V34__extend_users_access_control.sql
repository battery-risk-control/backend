-- 접근통제(계정 잠금·비밀번호 유효기간) 지원을 위한 users 확장.
-- 규제 가이드 제4조(접근통제)·비밀번호 규칙 대응. 기존 계정을 그대로 유지하기 위해 모두 추가(additive) 컬럼으로 둔다.

ALTER TABLE users
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN password_changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

COMMENT ON COLUMN users.failed_login_count IS '연속 로그인 실패 횟수. 로그인 성공 또는 비밀번호 재설정 시 0으로 리셋한다.';
COMMENT ON COLUMN users.locked_until IS '이 시각까지 로그인을 차단한다. NULL이거나 과거 시각이면 잠금 아님.';
COMMENT ON COLUMN users.password_changed_at IS
    '비밀번호 최종 변경 시각. +90일이 지나면 로그인 시 PASSWORD_EXPIRED로 재설정을 요구한다. 기존 계정은 이 마이그레이션 시점부터 90일 유예.';
