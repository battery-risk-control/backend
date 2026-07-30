# Changelog

## 2026-07-22 — C2 로그아웃 세션 PostgreSQL 영속화

- In-memory `ConcurrentHashMap` 블랙리스트 제거
- Flyway `V3__create_revoked_token_sessions.sql` 추가
- `RevokedTokenSession` JPA Entity와 Repository 추가
- `TokenBlacklistService`를 PostgreSQL 조회·저장·만료 행 정리 방식으로 교체
- Spring 재시작 후 DB 세션 행 유지와 로그아웃 Token HTTP 401 차단 검증

## 2026-07-22 — C1-A와 C2 로그인·인증·권한 통합

- Spring Security, BCrypt, JJWT Access/Refresh Token 인증 추가
- `users` 테이블을 Flyway `V2__create_auth_tables.sql`로 PostgreSQL에 적용
- 회원가입·로그인·갱신·로그아웃·현재 사용자 조회 API 추가
- `PURCHASING`, `STRATEGY`, `EXECUTIVE` 세 역할 회원가입 지원
- `/api/v1/dashboard/**` 공개, 그 외 `/api/v1/**` 인증 필요 정책 적용
- C1과 C2 공통 성공·오류 응답 및 `GlobalExceptionHandler` 병합
- 인증 JSON과 Swagger Schema를 `snake_case`로 통일
- Swagger Bearer Authorize 구성과 공개·보호 Operation 표시 분리
- 원본 C2 인증 4개 시나리오와 C1 테스트를 포함한 Spring 11개 테스트 통과
- 실제 PostgreSQL에서 V2, 세 역할 가입·로그인, 인증된 C1 업로드 E2E 검증

## 2026-07-22 — C1-A 업로드·영구 저장 완료

- 최소 Material/Supplier/Contract 참조 데이터를 반복 가능 Flyway Seed로 추가
- `contract_documents` JPA Entity·Repository와 PostgreSQL 중복 조회 구현
- UUID 기반 `uploads/contracts/{document_id}/original.{ext}` 원본 보관 구현
- 확장자·MIME·크기·경로·SHA-256 검증 및 파일/DB 실패 보상 처리 구현
- 문서 상태를 `PENDING → PROCESSING → COMPLETED/FAILED`로 영구 저장
- Spring이 발급한 `document_id`를 FastAPI 처리 API에서도 그대로 사용
- `GET /api/v1/documents/{document_id}` 처리 상태 조회 API 추가
- 실제 업로드·중복 차단·PostgreSQL 재시작 후 영속성 및 전체 테스트 검증 완료

## 2026-07-22 — 전체 API `snake_case` 전환

- Spring Jackson 전역 직렬화 규칙을 `SNAKE_CASE`로 변경
- FastAPI Pydantic camelCase Alias Generator를 제거하고 필드명을 그대로 직렬화
- JSON, Multipart Form, Spring–FastAPI 내부 통신 필드를 `snake_case`로 통일
- 실시간 관제 API를 조장 승인 `timestamp/alerts` 응답 구조로 교체
- `event_features` 매핑 필드와 `High/Medium/Low` 화면 계약 반영
- Interface Specification을 v0.2로 올리고 문서·테스트 예시를 `snake_case`로 변경

## 2026-07-22 — 프론트엔드 실시간 관제 API 계약

- Spring 공개 API `GET /api/v1/map/realtime-alerts` 추가
- 지도 좌표, 뉴스 요약, XGBoost Impact Domain, Severity, 원시 Feature snapshot 응답 정의
- 화면 설명용 `decisionReasons`와 Mock·모델·규칙 버전 표시 추가
- 당시 외부 JSON을 `camelCase`로 정리했으나 이후 팀 합의에 따라 `snake_case`로 전환
- 뉴스·모델 연동 전 결정론적 Mock 서비스와 MVC 계약 테스트 추가
- PostgreSQL 영구 데이터 쓰기 주체를 Spring Boot로 인터페이스 문서에서 바로잡음

## 2026-07-22 — PostgreSQL 기본 환경과 V1 Schema

- PostgreSQL 16 Docker Compose, Named Volume, Health Check 추가
- Spring Data JPA, PostgreSQL Driver, Flyway, Actuator 의존성 추가
- 환경 변수 기반 DataSource와 `ddl-auto: validate` 설정 추가
- F6 최소 Master Data인 `materials`, `suppliers`, `contracts` 테이블 추가
- C1 영구 저장 대상인 `contract_documents` 테이블과 상태·FK·Unique·Index 추가
- Flyway `V2`를 C2 인증·권한 Schema용으로 예약하고 Migration 규칙 문서화
- Compose 설정 검증과 Spring 전체 테스트 통과
- 실제 컨테이너·Flyway 실행 검증은 Docker Desktop 기동 후 진행 필요

## 2026-07-22 — C1 파일 구조 간소화

- Spring C1 구현을 `FastApiConfig`, `DocumentController`, `DocumentDto`, `GlobalExceptionHandler`, `DocumentService` 5개 파일로 통합
- Spring의 C1 전용 Domain·Repository·개별 DTO·개별 Exception 파일 제거
- FastAPI C1 구현을 `documents.py`, `document.py`, `document_service.py` 3개 파일로 통합
- FastAPI의 C1 전용 CRUD·Model·Repository 파일을 `document_service.py` 내부 구현으로 이동
- API 경로와 Request/Response 계약을 유지한 상태에서 FastAPI 28개 및 Spring 전체 테스트 통과

## 2026-07-21 — C1 문서 업로드 구현

- Spring Boot `POST /api/v1/documents` 업로드 API 추가
- Spring에서 PDF/TXT·파일 크기·필수 Metadata·SHA-256 중복 검증 추가
- Spring `config/controller/domain/dto/exception/repository/service` 계층 구현
- FastAPI `POST /api/v1/documents/process` 문서 처리 API 추가
- FastAPI `api/core/crud/models/repositories/schemas/services` 계층으로 문서 처리 분리
- TXT 추출, 실제 PDF 파싱, 청킹, In-memory 저장과 중복 검출 구현
- 기존 RAG 업로드·검색을 새 문서 Repository 구조에 연결
- FastAPI 28개 테스트와 Spring Boot 전체 테스트 통과

## 2026-07-21 — 통합 To Do List v0.3 작성

- ERP 영향 분석, RAG, 실시간 모니터링, 구매 브리핑 기능을 기존 로드맵에 통합
- Master Data, 데이터 계보, 규제 Hard Gate, 공급사 자격 검증 기능 추가
- 업로드, 인증·권한, 재시도, 감사 로그, Idempotency, 관측성, LLM 안전장치 추가
- 라벨·Dataset·Model Registry·시계열 검증·SHAP·Drift 운영 항목 추가
- 12~15순위와 MVP·운영·선택 고도화 범위 구분 추가
- 전체 문서와 Notion용 체크리스트 문서 추가

## 2026-07-21 — 백엔드 책임 구조 v0.2 계획 확정

- Spring Boot를 시스템 분석 오케스트레이터 및 PostgreSQL 단일 읽기·쓰기 주체로 재정의
- FastAPI를 LLM·XGBoost·Severity·RAG·브리핑 분석 전용 서비스로 재정의
- FastAPI의 PostgreSQL 직접 접근 계획 제거, ChromaDB 소유권만 유지
- Spring–FastAPI 연동을 2.5순위로 추가하고 분석 상태·실패·저장 흐름 정의
- 자동 분석 시작점을 Spring Scheduler로 변경
- 수정된 전체 To Do List를 `docs/backend-data-modeling-todo-v0.2.md`에 추가

## 2026-07-20 — FastAPI Mock 인터페이스 완성

- extraction, classification, severity, RAG, briefing 스키마를 도메인별 파일로 분리
- 내부 API의 `dict` 응답을 구체적인 Swagger response model로 교체
- 입력 기반 Extraction과 다중 Impact Domain 분류 Mock 구현
- NORMAL/WARNING/CRITICAL Severity 시나리오 및 계산 상세 추가
- `/analyze`의 네 가지 option이 실제 파이프라인 단계를 제어하도록 개선
- TXT/PDF Mock 업로드, chunking, embedding, metadata filter 검색 E2E 구현
- 의존성 주입과 테스트별 In-memory store 격리 추가
- 애플리케이션·validation·예상하지 못한 오류의 공통 envelope 처리
- 7개 API의 OpenAPI request/response schema 자동 검증 추가
- 실제 Uvicorn `/docs` 및 `/openapi.json` HTTP 200 확인, Swagger 화면 캡처 추가

## 2026-07-20 — 프로젝트 루트 통합

- 중첩된 프로젝트 두 벌을 바깥 프로젝트 루트로 평탄화
- 바깥 전용 ML/RAG 구현과 안쪽 서비스·repository 구조를 병합
- 문서, 테스트, Gradle Wrapper 및 설정 파일을 루트 기준으로 통일
- 중첩 원본과 병합 전 바깥 원본을 Git 제외 백업으로 보존
- 평탄화 후 Spring 및 FastAPI 전체 테스트 통과
- 바깥 프로젝트 루트에 `main` 브랜치 Git 저장소 초기화

## 2026-07-20 — FastAPI 교체 가능 구조 추가

- 환경 변수 기반 `core/config.py`와 명시적 Mock mode 추가
- extraction, classification, severity, ERP context 서비스를 분리
- XGBoost 교체 지점인 `ml/inference.py` 추가
- loader, chunker, in-memory vector store와 RAG 서비스 추가
- ERP/Risk repository Protocol 및 in-memory 구현 추가
- `/analyze`와 내부 API를 서비스 계층 기반으로 리팩터링
- 실제 LLM, XGBoost, PostgreSQL, ChromaDB 연동은 보류

## 2026-07-20 — Interface v0.1 보완

- Spring Risk Detail 응답을 명세의 중첩 DTO 구조로 변경
- 리스크·계약·브리핑별 404 오류 코드 추가
- Spring 잘못된 요청 공통 오류 응답과 CORS 설정 추가
- Spring API 통합 테스트 추가
- FastAPI 공통 validation/HTTP 오류 envelope 추가
- FastAPI 내부 개발용 Mock API와 테스트 추가
- 저장소 내 Interface Specification v0.1 문서 추가
