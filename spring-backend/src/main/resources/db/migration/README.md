# Flyway Migration 규칙

- 적용된 Migration 파일은 수정하지 않고 새 버전을 추가합니다.
- `V1`: F6 최소 Master Data와 C1 문서 Schema
- `V2`: C2 사용자·인증·권한 Schema
- `V3`: C2 로그아웃 세션 영속 블랙리스트 Schema
- `V4`: F6 외부 ERP ID 매핑, ERP Master·재고·소비·발주·입고 Schema
- `V5`: 11단계 Severity 입력 Snapshot·규칙 결과 Schema
- `V6` 이후: 팀 합의 후 순서대로 사용
- `R__insert_c1_reference_seed.sql`: C1 개발·검증용 최소 Material/Supplier/Contract Seed
- PostgreSQL 업무 테이블 Migration은 Spring Boot에서만 관리합니다.
- FastAPI에는 PostgreSQL Driver·ORM·Migration을 추가하지 않습니다.
