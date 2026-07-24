# Claude 협업 대화 기록

- 프로젝트: 배터리 원자재 공급망 리스크 관제 시스템
- 작업 경로: `C:\aivleschool\bigproject\battery-risk-mvp-starter`
- 정리 기준일: 2026-07-24
- 작성 목적: 이 세션에서 진행한 검증·구현·버그 수정 내역과 팀 논의 준비 내용을 한 문서에서 확인하기 위함

> 이 문서는 채팅 문장 전체를 그대로 옮긴 축어록이 아니다. 대화에서 확인된 사용자 요청, 합의된 결정, 실제 코드 변경과 검증 결과를 시간순·주제별로 재구성한 협업 기록이다.

---

## 1. 문서 업로드 3대 기능 구현 완료 확인

이전 세션에서 시작된 작업의 마무리로, 아래 3가지가 실제로 코드에 반영되어 있는지 확인하고 마무리했다.

1. **문서 종류 드롭다운**: `App.jsx`의 `<select>`를 `CONTRACT/PURCHASE_ORDER/SPECIFICATION/CERTIFICATE/OTHER`(계약서/발주서/사양서/인증서/기타)로 교체
2. **50MB 파일 크기 제한**: `documentApi.js`, `application.yml`, `DocumentService.java`에서 10MB → 50MB로 상향
3. **CSV 업로드 지원**: `documentApi.js`, `DocumentService.java`, `document_service.py`에 CSV 확장자·MIME 허용 추가

세 가지 모두 컴파일 확인 후 완료 처리했다.

---

## 2. 로컬 실행 환경 이슈 진단

S01~S08 마일스톤을 실제로 검증하려는 과정에서 두 가지 환경 문제를 순서대로 만났다.

### 2-1. Docker Desktop 미실행

`docker compose up -d postgres chroma` 실행 시 `dockerDesktopLinuxEngine` 파이프를 찾지 못하는 오류가 발생했다. 원인은 Docker Desktop 앱 자체가 꺼져 있었기 때문이며, 앱을 실행한 뒤 재시도해 해결했다.

### 2-2. Flyway 체크섬 불일치

`document_id` 형식을 `UUID` → `VARCHAR(40)`으로 바꾸며 `V1__create_master_and_document_schema.sql`을 직접 수정했는데, 기존 DB에는 이전 V1이 이미 적용되어 있어 Flyway가 시작을 거부했다. `docker compose down -v`로 볼륨을 초기화하고 V1~V5를 처음부터 재적용해 해결했다.

---

## 3. S01~S08 실제 E2E 검증 (Swagger)

볼륨 초기화 이후 Swagger에서 아래 순서로 실제 호출하며 검증했다.

```text
POST /api/v1/auth/signup
POST /api/v1/auth/login
POST /api/v1/documents           (신규 TXT 업로드)
GET  /api/v1/documents/{id}      (상태 조회)
POST /api/v1/documents/{id}/reprocess
POST /api/v1/rag/search
POST /api/v1/auth/logout
```

검증 도중 여러 번 사용자 실수(토큰에 따옴표 포함, `Bearer` 중복, 한글이 섞여 들어간 헤더)로 인한 401 오류가 발생했고, 매번 원인을 짚어 해결했다.

### 3-1. 발견하고 그 자리에서 수정한 버그 — ChromaDB `include=[]`

문서 재처리 시 `500 VECTOR_STORE_DELETE_FAILED`가 발생했다. FastAPI 로그에 트레이스백이 없어 코드 분석으로 원인을 추정했는데, `vector_store_service.py`의 `delete_document()`/`clear()`가 ChromaDB 1.5.9에서 허용하지 않는 `include=[]`(빈 리스트)를 넘기고 있었다. `include=["metadatas"]`로 수정한 뒤 `/reprocess`로 재검증해 `COMPLETED`, `chunk_count: 2`를 확인했다.

### 3-2. Swagger 스키마 snake_case 불일치 수정

Swagger의 "Example Value"가 실제 API 계약(snake_case)과 다르게 camelCase로 표시되는 문제를 발견했다. 원인은 springdoc이 스키마 생성 시 Spring이 관리하는 `SNAKE_CASE` `ObjectMapper`를 쓰지 않고 swagger-core 자체 기본 `ObjectMapper`를 쓰기 때문이었다. `OpenApiConfig.java`에 `ModelResolver` Bean을 추가해 동기화했다.

### 3-3. React 프론트엔드 확인

`npm run dev`로 띄운 React 화면에서 동일 계정으로 로그인 후 파일 업로드가 `PENDING → PROCESSING → COMPLETED`로 표시되고, 동일 문서 재업로드 시 `DUPLICATE` 배지가 뜨는 것을 화면으로 확인했다.

---

## 4. ERP 데이터(S09~S10) 검증

### 4-1. Seed 데이터 적재

`data/ERP_data/spring-csv/`(00_manifest.csv + 01~10 CSV 10개)를 `ERP_SEED_ENABLED=true`, `ERP_SEED_DIRECTORY` 환경변수로 지정해 `ErpSeedConfig.java`가 기동 시 자동 적재하는 것을 로그(`F6 ERP CSV seed completed: 10 files`)로 확인했다.

### 4-2. `/api/v1/erp/context` 검증

`MAT-LI-CARB`(리튬 탄산염) 자재를 조회해, CSV 원본 값(재고 스냅샷 4개 창고 합산, 공급사 의존도 0.45 등)과 API 응답을 직접 대조해 계산 로직이 정확함을 확인했다.

### 4-3. 타임존 정규화 이슈 (알려진 채로 보류)

`as_of`에 `+09:00` 오프셋을 넣어도 Jackson의 `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 기본 설정 때문에 서버 내부적으로 UTC 기준 날짜로 변환되어, 날짜 경계 계산이 하루 밀리는 현상을 발견했다. 지금 당장 비즈니스에 치명적이지 않아 추후 처리하기로 보류했다.

---

## 5. ERP 데이터의 성격 정정 — "Mock"이 아니라 "최종 데이터"

처음에는 ERP CSV 데이터를 "나중에 실제 ERP 연동으로 교체될 임시 데이터"로 이해하고 있었으나, 사용자가 **"회사 보안 정책상 실제 ERP에 직접 연결할 수 없어, 이 Mock 데이터가 사실상 최종 데이터"** 라고 정정했다.

근거를 재확인한 결과, 원본 기획 문서(S16 "실제 모델 교체" 절)의 교체 대상 목록에도 Embedding·LLM·XGBoost·브리핑만 있고 ERP 데이터는 애초에 포함되어 있지 않았다 — 이전의 "나중에 교체될 것"이라는 판단은 문서 근거가 아니라 어시스턴트의 잘못된 일반화였음을 확인하고 정정했다.

이 내용을 `docs/implementation-summary-functions-and-steps.md`에 반영했다.

---

## 6. ERP 데이터 갱신 기능 설계 논의

향후 회사에서 새 ERP 스냅샷을 받을 때마다 시스템에 반영할 방법이 필요하다는 논의가 이어졌다.

- **제안**: `ErpSeedConfig`의 CSV 적재 로직을 재사용해 HTTP 업로드 API(`POST /api/v1/erp/import` 가칭)로 노출
- **권한**: STRATEGY(경영기획) 역할만 허용 — 이 프로젝트 최초의 역할 기반 접근 제어가 됨. `CustomUserDetails`가 이미 `ROLE_STRATEGY` 권한을 부여하고 `@EnableMethodSecurity`도 이미 켜져 있어, `@PreAuthorize("hasRole('STRATEGY')")` 한 줄이면 구현 가능함을 확인했다.
- **UI 배치**: C1 문서 업로드(계약서 등, 전 사용자 대상)와 완전히 분리된 화면 — 이미 진행 중인 STRATEGY 대시보드 프론트엔드 작업에 자연스럽게 붙이기로 함. 드롭다운에 "ERP 데이터" 옵션을 추가하는 방식은 데이터 모델·업로드 방식이 근본적으로 달라 채택하지 않기로 함.

실제 구현은 팀 논의 이후로 미뤘다.

---

## 7. F/C/D/M 기능·S00~S16 마일스톤 이해 정리

사용자가 기능 정의(F1~F12, C1~C7, M1~M6, D1~D4)와 마일스톤(S00~S16)을 직접 정했고 책임지고 설명해야 하는 상황이라, "왜 필요한지"와 "왜 이 순서인지"를 주제별로 묶어 정리했다.

- 데이터 수집/정리, 리스크-업무 연결, 판단, 근거·설명, 표시·알림, 신뢰 기반의 6개 그룹으로 기능을 분류
- F12/C4/M1·M2·M4/M3·축소/M6/D2 등 "일부러 만들지 않거나 축소한" 항목의 이유도 함께 정리
- S00~S16은 "브리핑(S13)은 ERP·Severity·RAG(S10~S12) 세 재료가 모두 있어야 시작 가능"처럼 의존관계 중심으로 순서의 이유를 설명

---

## 8. 발표 자료 검증 — 실제 테스트로 확인, 버그 2건 발견·수정

사용자가 정리한 발표용 노션 요약을 검토하며, "검증했다"고 적힌 항목 중 실제로는 확인되지 않은 부분(로그아웃 API, CSV 실제 업로드, 50MB 경계값)을 짚었다. 이후 사용자 요청으로 어시스턴트가 직접 curl로 세 가지를 모두 테스트했다.

### 8-1. 로그아웃 — 정상 확인

`POST /api/v1/auth/logout` 호출(200) 후 같은 토큰으로 재요청 시 `401 AUTHENTICATION_REQUIRED`가 반환되어 블랙리스트 로직이 실제로 동작함을 확인했다.

### 8-2. CSV 업로드 — 정상 확인

실제 CSV 파일을 만들어 업로드해 `COMPLETED`, `chunk_count: 1`, `embedding_type: MOCK_TOKEN_HASH`까지 PostgreSQL에서 직접 재확인했다.

### 8-3. 발견한 버그 1 — 50MB 초과 시 500 (413/422가 아님)

51MB 파일 업로드 시 `application.yml`의 `max-file-size: 50MB`가 Spring Multipart Resolver 레벨에서 `MaxUploadSizeExceededException`을 던지는데, `GlobalExceptionHandler`가 이 예외를 처리하지 않아 `500 INTERNAL_SERVER_ERROR`로 응답하고 있었다.

**수정**: `GlobalExceptionHandler.java`에 `MaxUploadSizeExceededException` 핸들러를 추가해 `422 FILE_TOO_LARGE`로 응답하도록 변경.

### 8-4. 발견한 버그 2 — `erp_supplier_id` 생략 시 500

문서에는 "`erp_supplier_id`를 생략하면 우선순위 1 공급사를 자동 선택한다"고 되어 있었으나, 실제로 생략(`null`)하고 호출하면 PostgreSQL이 `could not determine data type of parameter $2` 오류를 내며 500이 발생했다. `NamedParameterJdbcTemplate`이 같은 이름의 파라미터를 여러 `?`로 전개하는데, `IS NULL`로만 쓰이는 자리에서는 타입을 추론하지 못하는 PostgreSQL JDBC의 특성 때문이었다.

**수정**: `ErpRepository.findSupply()`의 `erpSupplierId` 파라미터에 `java.sql.Types.VARCHAR` 타입 힌트를 명시.

### 8-5. 재검증

Spring Boot 재시작 후 두 버그 모두 재테스트해 정상 수정을 확인했다.

```text
51MB 업로드   → 422 FILE_TOO_LARGE (이전: 500)
erp_supplier_id 생략 → 200 OK, SUP-CHL-01 자동 선택 (이전: 500)
```

---

## 9. 이 세션에서 변경된 파일 목록

```text
frontend/src/App.jsx
frontend/src/documentApi.js
frontend/src/documentApi.test.js
spring-backend/.../application.yml
spring-backend/.../service/DocumentService.java
spring-backend/.../config/OpenApiConfig.java
spring-backend/.../exception/GlobalExceptionHandler.java
spring-backend/.../repository/ErpRepository.java
fastapi-ai/app/services/document_service.py
fastapi-ai/app/services/vector_store_service.py
docs/implementation-summary-functions-and-steps.md
```

---

## 10. 다음에 할 일

1. ERP 데이터 갱신(업로드) 기능 — 팀 논의 후 실제 구현 (`ErpSeedConfig` 로직 재사용 + STRATEGY 권한 제어)
2. `as_of` 타임존 정규화 이슈 — 필요 시 `application.yml`에 `spring.jackson.time-zone` 설정 또는 커스텀 Deserializer 적용
3. S12(RAG 대응 분석) 이후 마일스톤 — 아직 미구현 상태, 순서대로 진행
