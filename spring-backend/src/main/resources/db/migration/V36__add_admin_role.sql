-- 관리자(ADMIN) 역할 신설. 규제 가이드 시큐어코딩 5(관리자 권한 접속) 대응.
-- 가입 승인/거부는 ADMIN만 수행하도록 role CHECK 제약에 'ADMIN'을 추가한다.
-- V2에서 정의한 ck_users_role 제약을 DROP 후 재생성한다(기존 데이터는 3종뿐이라 재검증 통과).

ALTER TABLE users DROP CONSTRAINT ck_users_role;

ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('PURCHASING', 'STRATEGY', 'EXECUTIVE', 'ADMIN'));
