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

## 9. ERP 데이터 갱신 기능 신규 구현 (10개 엔티티 단건 Upsert)

팀 논의 결과 **ERP Mock 데이터가 이 프로젝트의 최종 데이터 소스**임이 확정되어(보안상 실제 ERP 연동 불가), CSV 일괄 시드 외에 **운영 중 단건 갱신** 수단이 필요해졌다. 사용자 지시로 아래를 확정하고 구현했다.

**확정 사항**
- 10개 ERP 테이블 전부를 단건 갱신 대상으로 한다 (재고만이 아니라 전체).
- 수정/신규를 사용자가 구분하지 않고 **외부 ERP ID로 자동 판별하는 단일 Upsert**로 한다.
- **역할 제한 없음** — PURCHASING/STRATEGY/EXECUTIVE 모두 사용 가능.
- 프론트엔드는 다른 팀원이 담당하므로 **백엔드 API + Swagger 검증까지만** 범위로 한다.

**신규 파일**: `ErpAdminController.java`(엔드포인트 10개), `ErpAdminService.java`, `ErpAdminDto.java`
**수정 파일**: `ErpRepository.java`(FK 조회 메서드 추가), `ErrorCode.java`(ERP FK 오류 코드 5개)

**핵심 설계**
- 일반 8개 테이블: `INSERT ... ON CONFLICT (erp_xxx_id) DO UPDATE` (CSV 시드가 쓰던 패턴 재사용)
- 스냅샷 2개(`inventory_snapshots`, `material_consumptions`): 기존 행을 `is_current=false`로 내리고 새 행 추가 (`@Transactional`)
- FK는 내부 PK가 아니라 외부 ERP 문자열 ID로 입력받아 변환

**검증 결과 (실제 서버 curl)**: 재고 갱신 → `/erp/context` 재조회 시 `on_hand 40320→35611`, `inventory_days 36→31.291`로 **S10 계산에 즉시 반영** 확인. 신규/갱신 자동 판별(`created` 플래그), FK 4개 변환, 없는 FK → 404, 3계층 역할 모두 200 확인.

---

## 10. ERP 정답셋 자동 검증 스크립트

`agent-csv`(회귀 테스트 정답셋)가 코드에서 전혀 사용되지 않고 있음을 확인하고, S10 완료 기준("Agent Expected 결과와 비교")을 코드화했다.

**신규 파일**: `scripts/verify_erp_context.py`

`01_erp_agent_requests.csv`의 10개 요청을 `/api/v1/erp/context`에 보내고 `04_erp_exposure_expected.csv`의 기대값(ERP Context 6개 필드)과 자동 대조한다. 실행 결과 8/10 PASS이며, 2건의 불일치는 코드 버그가 아님을 확인했다.

- REQ-001: 수동 검증 중 재고를 변경한 상태여서 발생 (baseline 복원 시 해소)
- REQ-007: 정답셋의 `nextEtaDays` 빈칸이 실제 설계와 불일치. 코드는 "사용량이 없어도 ETA는 반환"이 의도된 동작이며 `ErpFeatureTest.java:104`에 이미 못박혀 있음 → **정답셋 쪽 값이 보수적으로 비워진 것**

> 참고: exposure 점수 계열 컬럼(`erpExposureScore`, `exposureLevel`, `forcedCritical`)은 `/erp/context` 응답에 없는 별도 점수 엔진 소관이라 이 스크립트 범위 밖이다.

---

## 11. 발견·수정한 버그 (총 3건, 전부 같은 뿌리)

세 건 모두 **"예외 타입에 맞는 핸들러 부재 → 불친절한 500"** 이라는 동일한 문제였다.

| # | 증상 | 원인 | 수정 |
| --- | --- | --- | --- |
| 1 | 51MB 업로드 시 `500` | `MaxUploadSizeExceededException` 핸들러 없음 (Multipart Resolver 단계에서 발생해 `DocumentService.validate()`까지 도달조차 못 함) | `GlobalExceptionHandler`에 핸들러 추가 → `422 FILE_TOO_LARGE` |
| 2 | `erp_supplier_id` 생략 시 `500` | `NamedParameterJdbcTemplate`이 `IS NULL` 자리에만 쓰이는 파라미터의 타입을 PostgreSQL이 추론 못 함 (`could not determine data type of parameter $2`) | `ErpRepository.findSupply()`에 `java.sql.Types.VARCHAR` 타입 힌트 명시 → 우선순위 1 공급사 자동 선택 정상 동작 |
| 3 | `material_code` 중복 시 `500` | upsert가 `ON CONFLICT (erp_material_id)`만 처리. 다른 UNIQUE 제약(`uq_material_code` 등) 위반은 `DataIntegrityViolationException`으로 터지는데 핸들러 없음 | `GlobalExceptionHandler`에 핸들러 추가. DB 제약명을 읽어 한글 안내 → `409 DUPLICATE_KEY` (10개 admin API 전부에 일괄 적용) |

세 건 모두 수정 후 실제 서버로 재검증 완료.

---

## 12. RAG 검색 정밀 검증 (Metadata Filter 격리)

"검색이 결과를 반환한다"는 것만 확인됐던 상태라, **다른 계약서가 새어나오지 않는지**까지 검증했다. Windows curl의 한글 UTF-8 깨짐 문제를 피하기 위해 Python(urllib)으로 검증했다.

서로 다른 주제의 문서 2개를 다른 계약(계약2/계약3)으로 업로드한 뒤:

| 검증 | 결과 |
| --- | --- |
| 계약2 필터로 검색 | 계약2 청크만 반환, `가격 조정` 조항이 sim=0.40으로 최상위 |
| **계약3 필터로 계약2의 주제("가격 조정") 검색** | **계약2가 전혀 나오지 않고 계약3만 반환** (격리 성공) |
| 없는 계약(999) 필터 | 0건 |
| 필터 없는 검색 | `422 RAG_FILTER_REQUIRED`로 거부 |

문서1이 강조한 "다른 공급사 계약서 혼입 방지"(Metadata Hard Filter)가 실제로 작동함을 확인했다.

---

## 13. S11 Severity Rule Engine E2E 검증

코드는 있었으나 실제 서버로 검증된 적이 없어 E2E를 수행했다. **코드 수정 없이 한 번에 전부 통과**했다.

**FastAPI 계산 로직**: NORMAL(5.0) / CRITICAL(100) / FEOC Hard Gate(100, `forced_critical=true`) / UNKNOWN(`INSUFFICIENT_DATA`) / 결정성(동일 입력 → `data` 완전 동일) 확인.

**Spring 전체 파이프라인**: ERP Context 자동 결합 → severity 계산 → PostgreSQL 저장 → GET 조회 → 없는 ID 404 확인.

**가장 중요한 검증**: 실제 FEOC=YES 공급사(`SUP-CHN-01`, 중국 흑연)를 조회해 `MAT-GR-NAT`로 브리핑하면, ERP Context에 `feoc_status=YES`가 담기고 → FastAPI Severity가 다른 조건을 무시하고 CRITICAL로 강제 격상하는 **전체 사슬**이 작동함을 확인했다.

> WARNING 등급만 별도 시나리오로 이름 붙여 돌리지 않았고, 결정성 테스트(42.0)와 Spring E2E(67.0)에서 실제 산출되는 것을 확인했다.

---

## 14. S12·S13 신규 구현 (방식 A: 통합 브리핑)

S12(RAG 대응 분석)와 S13(템플릿 브리핑)을 **엔드포인트 하나로 통합**하는 방식을 사용자가 선택했다(파일 수와 구현 복잡도가 낮은 쪽).

### LLM 없이 템플릿을 만드는 방법

핵심은 **"AI가 새로 생각하게 하지 않고, 이미 계산된 사실을 고정 문장 틀에 끼워 넣는 것"**이다. 규칙으로 문장을 고르고 값만 채우므로 **동일 입력 → 동일 출력**이 보장된다.

```python
if inventory_days < safety_stock_days:
    text += f" 안전재고 기준({safety_stock_days:g}일) 미만이라 주의가 필요합니다."
```

계약 근거는 항상 `"담당자 검토가 필요합니다"`로만 표현하고, 근거가 없으면 `"근거 부족"`으로 명시한다. **"계약 위반 확정" 같은 새 판단은 절대 만들지 않는다.** S16에서 이 템플릿 함수만 LLM 호출로 교체하면 된다.

### 구현 파일

**FastAPI**
- `app/schemas/briefing.py` — `BriefingComposeRequest/Result`, `ContractEvidenceItem`, `AlternativeSupplierItem` 추가 (기존 `BriefingGenerationRequest`는 orchestration 호환을 위해 유지)
- `app/services/briefing_service.py` — `compose()` 신규. 섹션별 템플릿 규칙(`_inventory_summary`, `_contract_evidence_summary` 등)으로 결정적 조립
- `app/api/v1/internal.py` — `POST /api/v1/internal/briefings/compose` 추가

**Spring (신규 5개)**
- `dto/BriefingDto.java` — 외부 요청/FastAPI 통신/저장 결과 DTO
- `service/BriefingService.java` — ERP Context(S10) → Severity(S11) → RAG 검색(F2) → 적격 대체공급사(F9) → FastAPI 조립 → 저장
- `repository/BriefingRepository.java` — JSONB 저장·조회
- `controller/BriefingController.java` — `POST /api/v1/briefings`, `GET /api/v1/briefings/{id}`
- `db/migration/V6__create_briefings.sql` — briefings 테이블(JSONB 5개 컬럼)

**수정**: `ErpRepository.findEligibleAlternativeSuppliers()` 추가(F9 — 인증·FEOC·승인상태 포함 후보 목록), `ErrorCode` 3개 추가

기존 `ErpService`·`RagService`·`ErpRepository`·`SeverityDto`를 그대로 재사용했다.

### E2E 검증 결과 (코드 수정 없이 한 번에 통과)

**FastAPI 템플릿 조립 5개 시나리오**

| 시나리오 | 결과 |
| --- | --- |
| 근거 부족(계약서 없음) | "관련 계약 조항을 찾지 못했습니다. (근거 부족 — 담당자 확인 필요)" |
| 계약 근거 + 대체공급사 있음 | 조항 인용 + 페이지·유사도 표시, 인증·Lead Time 포함 후보 목록 |
| FEOC + 재고부족 + STALE | "FEOC 규제 우려 대상" 문구 + 데이터 품질 경고 자동 추가 |
| 사용량 없음(UNKNOWN) | "재고 소진 일수를 계산할 수 없습니다 (UNKNOWN)" |
| 결정성 | 동일 입력 → 동일 브리핑 |

**Spring 전체 파이프라인**

| 검증 | 결과 |
| --- | --- |
| MAT-LI-CARB 정상 브리핑 | ERP(재고 36일) + Severity(WARNING/67) + RAG 근거 2건 + 대체공급사 2곳 조립 |
| MAT-GR-NAT FEOC Hard Gate | CRITICAL/100, FEOC 경고 + 전용 권장 조치 자동 추가 |
| 저장 후 GET 조회 | JSONB 복원 정상(evidence 2건, checks 3개) |
| 없는 브리핑 조회 | `404 BRIEFING_NOT_FOUND` |
| 결정성 | 동일 요청 2회 → 본문 완전 동일 |

가장 의미 있는 결과는 두 시나리오의 대비다. 리튬은 계약서가 ChromaDB에 있어 **RAG로 조항 2건을 찾아 근거로 첨부**했고, 흑연은 계약서가 없어 **"근거 부족"으로 정직하게 표시**하고 대신 FEOC 경고·대체 조달 검토를 권장 조치에 자동 추가했다. 즉 **재고 관점(숫자)과 계약 관점(문서 근거)이 하나의 결론으로 뭉개지지 않고 각각 별도 섹션으로 유지**되는 문서1의 핵심 원칙이 실제로 지켜졌다.

또한 Severity는 `rule_version`, 브리핑은 `template_version`을 각각 반환해 **어떤 규칙·템플릿으로 만들어졌는지 추적 가능**하며, 모든 브리핑에 Mock 경고가 자동으로 붙는다.

---

## 14-1. 프론트엔드 ↔ 백엔드 격차 분석

프론트엔드 레포(`C:\aivleschool\bigproject\frontend\frontend`, Phase 9.4까지 진행)를 직접 읽고 백엔드와 대조했다. 상세 결과는 [`docs/frontend-backend-gap-analysis.md`](frontend-backend-gap-analysis.md).

**핵심 발견**
- 프론트엔드에 **실제 HTTP 호출이 하나도 없다** — `src/` 전체에 `fetch`/`axios` 없음. 전부 `api/*.api.ts` mock이며, `api/types.ts`를 "잠정 계약"으로 선언해두고 구현부만 교체하면 되도록 격리해둔 상태.
- **중심 개념이 다르다**: 프론트엔드는 `risk_event`(뉴스 사건), 백엔드는 자재·공급사·재고(ERP). 백엔드에 `risk_events` 테이블 자체가 없다(F3/F4 미구현).
- 프론트엔드가 기대하는 API 14개 중 **7개는 백엔드에 데이터가 아예 없다**(글로벌 리스크 맵, 뉴스 속보, 가격 추이, 사업부별 노출도 등 — 전부 F3/F4 영역).
- 즉시 해결 가능한 불일치 5개: `org_tier` 값(`planning` vs `STRATEGY`), 로그인 식별자(`email` vs `username`), 승인 대기(PENDING) 개념 부재, `confidence_label` 대응 필드 부재, 리스크 등급 체계 차이.

---

## 14-2. 인증 연동 — 백엔드를 프론트엔드 계약에 맞춤

사용자 방침("협의 없이 백엔드가 프론트엔드에 맞춘다")에 따라 백엔드를 수정했다. **기존 API·테스트를 깨지 않는 하위 호환 방식**을 택했다(C2 인증은 다른 팀원 담당 영역이므로).

### 핵심 설계: DB는 그대로, API 계층에서만 변환

가장 큰 불일치였던 `org_tier`(백엔드 `STRATEGY` ↔ 프론트엔드 `planning`)를 **마이그레이션 없이** 해결했다.

```java
public enum Role {
    PURCHASING("purchasing"),
    STRATEGY("planning"),      // ← 단어가 다른 지점을 여기서만 흡수
    EXECUTIVE("executive");
}
```

### 변경 내역

| 항목 | 방식 |
| --- | --- |
| `V7__extend_users_for_frontend_auth.sql` | `email`·`org_name`·`approval_status` 추가. 기존 계정은 `APPROVED` 기본값이라 그대로 로그인 가능 |
| `Role.java` | `getOrgTier()`/`fromOrgTier()` 매핑 추가 |
| `LoginRequest` | `email`·`username` 둘 다 optional, `loginId()`가 선택 |
| `SignupRequest` | 프론트엔드형(`org_tier`)·기존형(`role`) 둘 다 수용 |
| `CustomUserDetailsService` | username → email 순으로 조회 |
| `LoginResponse`·`UserSummary` | 기존 필드 유지하고 `org_tier`/`status`/`user_id`만 **추가** |
| `AuthService.login()` | 승인 전 계정은 `403 PENDING_APPROVAL` |
| `AuthController` | `POST /auth/users/{id}/approve` 신규(없으면 PENDING이 막다른 길) |

**설계 판단**: `org_tier`로 가입하면 PENDING, `role`로 가입하면 즉시 APPROVED로 갈리게 했다. 프론트엔드 승인 플로우는 살리면서 기존 테스트·검증 스크립트는 깨지지 않게 하기 위해서다.

### 검증 결과

기존 `AuthFlowTest` 4개 전부 통과(하위 호환 확인) + E2E 6가지 통과:
프론트엔드형 회원가입 → `PENDING` / 승인 전 로그인 → `403 PENDING_APPROVAL` / 승인 → 로그인 시 `org_tier: planning` + `status: APPROVED` / 기존 username 로그인 유지 / 3계층 매핑 / `GET /me`에서 `role: STRATEGY` ↔ `org_tier: planning` 확인.

실제 응답 JSON 7종(회원가입·로그인 성공/PENDING/실패·중복·`/me`)을 캡처해 [`docs/auth-integration-handoff.md`](auth-integration-handoff.md)에 정리했다. 프론트엔드 담당자에게 이 문서 하나만 전달하면 된다.

---

## 14-3. S14 조회·집계 API 구현 및 검증

### 구현

| API | 용도 |
| --- | --- |
| `GET /api/v1/dashboard/summary` | 등급별 건수 + 전체 데이터 규모 |
| `GET /api/v1/dashboard/materials` | 자재별 현재 리스크(게이지·스코어카드용) |
| `GET /api/v1/dashboard/import-dependency` | 공급사 의존도 분해(도넛차트용) |
| `GET /api/v1/contracts` | 계약 목록(페이지네이션) |
| `GET /api/v1/briefings` | 브리핑 목록(필터·페이지네이션) |

**신규 파일**: `DashboardDto`/`DashboardRepository`/`DashboardService`/`DashboardController`, 공통 `PageResponse`

**핵심 설계**: 등급별 집계는 `DISTINCT ON (material_id)`로 **자재별 최신 1건**만 센다. 브리핑처럼 이력이 쌓이는 구조라 누적 건수를 세면 같은 자재를 3번 분석했을 때 "심각 3건"이 되기 때문이다.

`/api/v1/risks`는 `risk_events` 테이블이 없어 만들지 않았다(F3/F4 영역).

### 검증 중 발견해서 고친 문제 2가지

**① 보안 구멍 — 대시보드가 무인증 공개 상태였다**

`SecurityConfig`에 `/api/v1/dashboard/**` permitAll이 있었다. 원래는 빈 경로라 무해했지만, 새 API가 **재고일수·공급사 의존도 같은 ERP 내부 정보**를 반환하므로 그대로 두면 로그인 없이 유출된다. 인증 필수로 바꾸고 관련 테스트도 새 동작에 맞게 수정했다.

> 프론트엔드에 비로그인 공개 대시보드가 있으므로, 공개용 데이터(뉴스·가격 추이)를 만들 땐 별도 경로에 `permitAll`을 두라는 주석을 남겼다.

**② 브리핑이 분석 이력을 남기지 않던 문제**

브리핑을 3건 만들었는데 대시보드는 "분석된 자재 0종"으로 나왔다. S13에서 `BriefingService`가 FastAPI severity를 직접 호출하면서 **계산한 위험 등급을 브리핑 안에만 저장하고 `severity_assessments`에는 남기지 않았기** 때문이다. 브리핑만 만든 사용자는 대시보드가 비어 보였다.

수정 후 `MAT-CU-FOIL` 브리핑을 만들자 분석자재 3→4로 증가하고 목록에 `WARNING/35.0`으로 즉시 등장했다. 문서1의 **F7(근거 계보 — 분석 결과 보존)** 원칙에도 맞는 수정이다.

### 최종 검증 (12개 전부 통과)

집계 정확성 / 심각도 순 정렬 / severity 필터 / 잘못된 값 400 / 수입 의존도(점유율 합계 **1.0**) / 없는 자재 404 / 계약 페이지네이션(29건, 10페이지) / 브리핑 목록·필터 / **무인증 401** / **브리핑→대시보드 자동 반영** / 등급별 합계 == 분석자재 수.

---

## 15. 이 세션에서 변경·생성된 파일

**수정**
```text
frontend/src/App.jsx, documentApi.js, documentApi.test.js
spring-backend/.../application.yml
spring-backend/.../service/DocumentService.java
spring-backend/.../config/OpenApiConfig.java
spring-backend/.../exception/GlobalExceptionHandler.java   (버그 2건 수정)
spring-backend/.../exception/ErrorCode.java
spring-backend/.../repository/ErpRepository.java            (버그 1건 + F9 메서드)
fastapi-ai/app/services/document_service.py
fastapi-ai/app/services/vector_store_service.py             (ChromaDB include=[] 버그)
fastapi-ai/app/schemas/briefing.py
fastapi-ai/app/services/briefing_service.py
fastapi-ai/app/api/v1/internal.py
docs/implementation-summary-functions-and-steps.md

# 인증 연동(14-2)
spring-backend/.../domain/Role.java                          (org_tier 매핑)
spring-backend/.../domain/User.java                          (email·승인상태)
spring-backend/.../dto/auth/LoginRequest.java                (email 로그인)
spring-backend/.../dto/auth/SignupRequest.java               (org_tier 수용)
spring-backend/.../dto/auth/LoginResponse.java, UserSummary.java
spring-backend/.../security/CustomUserDetailsService.java    (email 조회)
spring-backend/.../service/AuthService.java, UserService.java
spring-backend/.../controller/AuthController.java            (승인 API)

# S14(14-3)
spring-backend/.../config/SecurityConfig.java                (보안 구멍 수정)
spring-backend/.../service/BriefingService.java              (분석 이력 저장 추가)
spring-backend/.../repository/BriefingRepository.java        (목록 조회)
spring-backend/.../controller/BriefingController.java        (목록 API)
spring-backend/.../dto/BriefingDto.java
spring-backend/src/test/.../AuthFlowTest.java                (대시보드 인증 필수로 변경)
```

**신규**
```text
spring-backend/.../controller/ErpAdminController.java
spring-backend/.../service/ErpAdminService.java
spring-backend/.../dto/ErpAdminDto.java
spring-backend/.../controller/BriefingController.java
spring-backend/.../service/BriefingService.java
spring-backend/.../repository/BriefingRepository.java
spring-backend/.../dto/BriefingDto.java
spring-backend/.../db/migration/V6__create_briefings.sql
spring-backend/.../db/migration/V7__extend_users_for_frontend_auth.sql
spring-backend/.../domain/ApprovalStatus.java
spring-backend/.../dto/DashboardDto.java
spring-backend/.../dto/PageResponse.java
spring-backend/.../repository/DashboardRepository.java
spring-backend/.../service/DashboardService.java
spring-backend/.../controller/DashboardController.java
scripts/verify_erp_context.py
docs/frontend-backend-gap-analysis.md
docs/auth-integration-handoff.md
```

---

## 16. 마일스톤 현황

```text
S00 ~ S14   완료·검증
S15         미구현 (Docker 통합·장애 검증)
S16         보류 (실제 모델 전달 대기)
```

이번 세션에서 **S07 재검증부터 S14 구현·검증까지** 진행했다. 뼈대 17단계 중 15개가 완료됐고, 남은 것은 S15(Docker 통합)와 외부 의존인 S16뿐이다.

여기에 더해 로드맵에 없던 작업 3가지를 추가로 했다.
- ERP 데이터 갱신 기능(10개 엔티티 단건 Upsert API)
- 프론트엔드 격차 분석 + 인증 연동(백엔드를 프론트엔드 계약에 맞춤)
- ERP 정답셋 자동 검증 스크립트

## 17. 발견·수정한 버그 총정리 (5건)

| # | 증상 | 원인 | 수정 |
| --- | --- | --- | --- |
| 1 | 51MB 업로드 시 500 | `MaxUploadSizeExceededException` 핸들러 부재 | `422 FILE_TOO_LARGE` |
| 2 | `erp_supplier_id` 생략 시 500 | PostgreSQL이 `IS NULL` 자리 파라미터 타입 추론 실패 | `Types.VARCHAR` 힌트 명시 |
| 3 | `material_code` 중복 시 500 | `DataIntegrityViolationException` 핸들러 부재 | `409 DUPLICATE_KEY` + 제약명별 한글 안내 |
| 4 | 대시보드가 무인증 공개 | `SecurityConfig` permitAll에 `/dashboard/**` 포함 | 인증 필수로 변경 |
| 5 | 브리핑이 대시보드에 안 잡힘 | 브리핑이 계산한 Severity를 이력으로 저장 안 함 | `severity_assessments`에도 저장 |

1~3은 "예외 타입에 맞는 핸들러 부재 → 불친절한 500"이라는 같은 뿌리였고, 4~5는 S14 검증 중 발견했다.

## 18. 다음에 할 일

1. **프론트엔드 인증 연동** — 백엔드 준비 완료. `docs/auth-integration-handoff.md`를 프론트엔드 담당자에게 전달하고 협의(백엔드 주소 설정 방식, 응답 래퍼 처리, 테스트 계정 시드 여부 등 7가지)
2. **S15 Docker 통합·장애 검증** — 전체 서비스를 하나의 Compose로
3. `as_of` 타임존 정규화 이슈 (보류 중)
4. `agent-csv` 정답셋 REQ-007의 `nextEtaDays` 빈칸 — 실제 설계와 불일치, 정답셋 수정 여부 팀 확인
5. `risk_events` 도입 여부 — 프론트엔드 화면 절반이 요구하지만 F3/F4(다른 담당) 영역. 팀 논의 필요
6. S16 실제 모델 교체 (모델 전달 대기)
