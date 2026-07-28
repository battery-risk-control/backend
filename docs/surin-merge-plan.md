# surin → merge 병합 플랜

- 작성일: 2026-07-28
- 대상(target): `C:\aivleschool\bigproject\backend_merge` (여기로 병합)
- 원본(source): `C:\aivleschool\bigproject\backend_surin`
- 병합 대상 기능: **F3(AI 분석) · F4(외부 데이터 수집) · F9(대체 공급사 추천) · F10(알림/에스컬레이션)**

---

## TL;DR

- **"F3/F9 파일 복붙"은 지금 surin 코드에선 성립하지 않는다.** 순수 복붙이 안전한 건 신규 leaf 파일(Java 신규 31 + FastAPI 12)뿐이고, 이마저도 아래 마이그레이션 재채번과 공유 파일 병합이 끝난 뒤에만 컴파일된다.
- **두 V1~V10 마이그레이션 체인을 "이어붙이는" 것은 불가능하다.** surin V4~V10을 재채번해도 surin V6가 merge V4가 이미 만든 컬럼(material_category 등)을 다시 `ADD COLUMN` → `already exists` 실패. 유일하게 성립하는 모델은 **merge V1~V8을 base로 고정 + surin 변경분을 additive delta(V9~)로 재표현**.
- 실제 작업은 **3-way 수동 병합**이며, 위험 구간은 3곳:
  1. **마이그레이션** (버전 번호·컬럼 충돌) → 재채번으로 해결
  2. **ERP 계층** (양쪽이 각자 seed/expose 구현)
  3. **FastAPI analyze 파이프라인** (severity/extraction/orchestration 양쪽 동시 수정)

### 4-Zone 관점 ("합쳤을 때 모든 기능이 도는가" 검증 결과)

| Zone | 대상 | 판정 |
|---|---|---|
| A | surin 전용 신규 테이블(analyses·collection·recommendations·notification) | ✅ 완전 disjoint → 재채번만 하면 동작 |
| B | ERP 마스터(surin V6 ↔ merge V4) | ✅ merge V4 ⊇ surin V6(전용컬럼 0개) + raw SQL(엔티티 충돌 없음) + **merge 시드가 supplier_materials 공급** → surin F9 동작. surin V6 폐기·시드 단일화 |
| C | documents(V1 국소 차이) | ⚠️ **유일하게 스키마+코드 화해 필요**: merge의 String `Document.java` 유지 + surin 문서로직 이식 + `document_type` CHECK 합집합(V13) |
| D | 공유 로직(auth/security/exception/FastAPI analyze) | 로직 수동 병합(스키마 무관) |

---

## 0. 전제 / 주의사항

- **surin 정본 폴더는 최상위 `spring-backend/` 와 `fastapi-ai/`** 다. (git 추적됨)
- surin의 **`backend/` 폴더는 untracked 잔재**(구버전 스냅샷)이므로 **무시**한다. 병합에 절대 쓰지 말 것.
- backend_merge의 DB는 이미 merge 스키마(V1~V8)가 적용된 상태(계약 1건 존재)이므로, **V1~V8은 건드리지 않고 V9부터 얹는다.**

---

## 1. 마이그레이션 병합 (핵심)

### 1-1. 현황: 버전 번호가 정면 충돌

| 버전 | surin | merge(minji) | 처리 |
|---|---|---|---|
| V1 | master_and_document | master_and_document | ⚠️ 내용 다름 → §2 참조 |
| V2, V3 | auth / revoked_token | 동일 | 그대로 |
| V4 | create_analyses | **extend_erp_master** | 재채번 |
| V5 | collection_tables | severity_assessments | 재채번 |
| V6 | extend_erp_schema | briefings | **폐기**(merge V4가 상위호환) |
| V7 | relax_material_usage | users_frontend_auth | **폐기**(merge V4가 이미 nullable) |
| V8 | supplier_recommendations | briefing_lineage | 재채번 |
| V9 | notification_log | (없음) | 재채번(email 라인 제거) |
| V10 | drop_analyses_briefing_id | (없음) | V9에 흡수 |

### 1-2. surin V6/V7은 폐기 (merge V4와 중복·상위호환 확인됨)

merge `V4__extend_erp_master_and_operations.sql`가 이미 다음을 전부 포함:
- materials: `material_category`, `criticality`, `erp_group_code` (+ surin에 없는 `erp_material_id`)
- suppliers: `supplier_status`, `risk_level`, `certifications` (+ `erp_supplier_id`)
- contracts: `material_id`, `document_source`, `document_path`, `contract_role`, `supplier_approval_status`, `ck_contract_status` 재정의 (+ `erp_contract_id`, `source_document_id`)
- `warehouses`, `supplier_materials` 테이블 생성
- `material_consumptions.average_daily_usage` **nullable** + `ck_consumption_usage` CHECK

→ surin V6(extend_erp_schema)·V7(relax)는 실행 시 전부 `already exists` 에러. **폐기한다.**

> ⚠️ **V6 폐기 단서**: merge V4는 surin V6의 상위집합이라 컬럼/테이블 누락은 없지만 **더 엄격**하다.
> - 더 많은 NOT NULL: `supplier_materials.contract_id`, `lead_time_days`, `minimum_order_quantity` 등 (surin V6는 nullable)
> - 더 많은 CHECK: `ck_contract_role`(PRIMARY/ALTERNATIVE), `ck_contract_supplier_approval`, `supplier_materials.approved_status`에 REJECTED 포함, 재고 수량 정합성 등
> - 일부 VARCHAR 더 짧음: `material_category` 50→40 등 (`ddl-auto: validate`이나 Hibernate는 길이 미검증, 타입 불일치만 부팅 실패)
>
> → 실제 파손 가능 경로는 surin `ErpSeedLoaderService`가 mock 데이터를 insert할 때 위 제약 위반 시뿐. **ERP seed를 merge `ErpSeedConfig`로 단일화(§3-C)하면 해소.**

> ✅ **V7 폐기 완전 안전**: merge V4가 이미 `average_daily_usage` nullable + `data_quality_flag`에 `MISSING_USAGE` 포함. surin V7은 no-op.
> ✅ **V10 폐기(흡수) 안전**: V9에서 `briefing_id`를 애초에 안 만듦 → drop 대상 없음. `Analysis.java`에 `briefingId` 필드 없음(확인).

### 1-3. 새로 만들 마이그레이션 (merge에 V9부터 추가)

> 경로: `spring-backend/src/main/resources/db/migration/`

#### `V9__create_analyses.sql`
surin V4에서 **`briefing_id` 제외**(V10 drop 반영) + surin V8의 analyses 컬럼 2개 흡수.

```sql
CREATE TABLE analyses (
    analysis_id     UUID PRIMARY KEY,
    material_id     BIGINT,
    supplier_id     BIGINT,
    event_title     VARCHAR(500) NOT NULL,
    event_content   TEXT NOT NULL,
    source_name     VARCHAR(200),
    country_code    VARCHAR(2),
    status          VARCHAR(20) NOT NULL,
    impact_domain   VARCHAR(50),
    severity        VARCHAR(20),
    severity_score  DOUBLE PRECISION,
    confidence      DOUBLE PRECISION,
    reason_codes    TEXT,
    rule_version    VARCHAR(100),
    material_category      VARCHAR(50),   -- surin V8에서 흡수
    recommendation_caveats TEXT,          -- surin V8에서 흡수
    mock            BOOLEAN NOT NULL DEFAULT FALSE,
    error_code      VARCHAR(100),
    error_message   VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_analyses_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_analyses_status ON analyses (status);
```
> ✅ 확인됨: surin `Analysis.java`에 `briefingId` 필드 없음 + `materialCategory`·`recommendationCaveats` 필드 존재 → 위 V9 통합안과 정확히 일치.

#### `V10__create_collection_tables.sql`
surin V5 **그대로** (raw_events, collection_cursors). `raw_events.triggered_analysis_id`는 UUID 컬럼일 뿐 FK 제약 없음 → 순서 무관.

#### `V11__create_analysis_supplier_recommendations.sql`
surin V8에서 **analyses ALTER 2줄 제거**(V9로 이동), 테이블 정의만:
```sql
CREATE TABLE analysis_supplier_recommendations (
    id BIGSERIAL PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analyses (analysis_id),
    supplier_id BIGINT NOT NULL,
    supplier_code VARCHAR(50) NOT NULL,
    supplier_name VARCHAR(150) NOT NULL,
    rank_position INTEGER NOT NULL,
    pros TEXT, cons TEXT,
    recommendation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_analysis_supplier_rank UNIQUE (analysis_id, rank_position)
);
CREATE INDEX idx_asr_analysis ON analysis_supplier_recommendations (analysis_id);
```

#### `V12__create_notification_log.sql`
surin V9에서 **`ALTER TABLE users ADD COLUMN email` 제거**(merge V7이 이미 추가), notification_log만:
```sql
CREATE TABLE notification_log (
    id BIGSERIAL PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analyses (analysis_id),
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_analysis_channel_recipient UNIQUE (analysis_id, channel, recipient)
);
CREATE INDEX idx_notification_log_analysis ON notification_log (analysis_id);
```

### 1-4. 재채번 요약

| 신규 파일 | 출처 | 변형 |
|---|---|---|
| V9 create_analyses | surin V4 (+V8 컬럼, −V10 briefing_id) | 통합 |
| V10 create_collection_tables | surin V5 | 그대로 |
| V11 create_analysis_supplier_recommendations | surin V8 | analyses ALTER 제거 |
| V12 create_notification_log | surin V9 | users.email 제거 |
| **V13 extend_document_type_check** ★ | 신규(화해) | ck_document_type을 두 taxonomy 합집합(10종)으로 확장 — §2 |
| ~~surin V6, V7, V10~~ | — | 폐기/흡수 |

#### `V13__extend_document_type_check.sql` (신규 화해 마이그레이션)
merge의 `ck_document_type`은 {CONTRACT, PURCHASE_ORDER, SPECIFICATION, CERTIFICATE, OTHER}로 제한 → surin이 넣는 {LTA, PURCHASE_GUIDELINE, SUPPLIER_EVALUATION, QUALITY_CERTIFICATE, REGULATION, TECHNICAL_SPEC} 삽입 시 CHECK 위반. 합집합으로 확장. ⚠️ **실제 테이블명은 `documents`가 아니라 `contract_documents`**(V1 확인, Flyway 검증에서 오류로 확인됨):
```sql
ALTER TABLE contract_documents DROP CONSTRAINT ck_document_type;
ALTER TABLE contract_documents ADD CONSTRAINT ck_document_type CHECK (document_type IN (
    'CONTRACT','PURCHASE_ORDER','SPECIFICATION','CERTIFICATE','OTHER',
    'LTA','PURCHASE_GUIDELINE','SUPPLIER_EVALUATION','QUALITY_CERTIFICATE','REGULATION','TECHNICAL_SPEC'
));
```
> 대안: surin 문서 적재 코드에서 타입을 merge 5종으로 매핑(LTA→CONTRACT 등) — 단 taxonomy 손실. **확장(무손실) 권장.**

---

## 2. V1 스키마 불일치 (해결책 확정)

surin V1 ↔ merge V1 실제 차이:

> 실제 테이블명은 **`contract_documents`**(V1). 아래 "documents"는 이 테이블을 가리킴.

| 항목 | surin | merge |
|---|---|---|
| `contract_documents.document_id` | `UUID` | `VARCHAR(40)` |
| document_type enum | LTA, PURCHASE_GUIDELINE, SUPPLIER_EVALUATION, QUALITY_CERTIFICATE, REGULATION, TECHNICAL_SPEC | CONTRACT, PURCHASE_ORDER, SPECIFICATION, CERTIFICATE, OTHER |

> ✅ **V1 차이는 `contract_documents` 테이블 한 곳에 국한**됨(materials/suppliers/contracts 베이스 테이블은 surin=merge 동일 확인). `suppliers.feoc_status`도 양쪽 V1에 동일 존재 → F9 필터 정상.

- backend_merge DB에 merge V1이 **이미 적용됨** → V1을 바꾸면 DB 리셋 필요. **merge V1 유지**가 강제됨.
- **`document_id`**: **merge의 `Document.java`(이미 `String`/VARCHAR(40)) 유지.** surin의 문서 처리 로직(현재 `UUID` 전제)을 String id로 이식 — surin도 이미 `UUID.randomUUID().toString()`·`get(String)`을 써서 이식 용이. ⚠️ merge `Document.java`를 surin UUID 버전으로 덮으면 `ddl-auto: validate` 부팅 실패 → **통째 복붙 금지**.
- **`document_type`**: **V13 화해 마이그레이션으로 CHECK 합집합 확장**(§1-3) → surin의 LTA 등 taxonomy 손실 없이 양쪽 공존.
- 조정 대상 코드: `Document.java`, `DocumentDto`, `DocumentService`, `DocumentRepository`(Spring) + `schemas/document.py`, `services/document_service.py`(FastAPI).

---

## 3. Spring Java 병합

### 3-A. 순수 신규 (32개, 복붙 31개)
> merge에 없는 파일 32개(`notification/` 3파일 포함). §1 마이그레이션 반영 후 복사. **`ErpSeedLoaderService` 1개만 폐기 → 실제 복붙 31개.**

- controller: `AnalysisController`, `CollectionController`, `SupplierController`
- domain: `Analysis`, `AnalysisSupplierRecommendation`, `CollectionCursor`, `NotificationLog`, `RawEvent`
- dto: `AnalysisDto`, `CollectionDto`, `ErpExposureDto`, `ExtractionDto`, `SupplierDto`
- repository: `AnalysisRepository`, `AnalysisSupplierRecommendationRepository`, `CollectionCursorRepository`, `NotificationLogRepository`, `RawEventRepository`
- service: `AnalysisService`, `CollectionAdapters`, `CollectionService`, `ErpExposureRequestService`, ~~`ErpSeedLoaderService`~~ (폐기·복붙 제외 → §3-C), `ExtractionClient`, `GdeltEventArchiveService`, `HistoricalDataImportService`, `HistoricalFeatureJoinService`, `NotificationService`, `SupplierQualificationService`
- service/notification: `EmailNotificationChannel`, `NotificationChannel`, `NotificationDeliveryException`

### 3-B. 공유 파일 = 수동 병합 (23개, 덮어쓰기 금지)
> 양쪽이 각자 수정. 손으로 합쳐야 함.

`OpenApiConfig`, `SecurityConfig`, `AuthController`, `DocumentController`, `ErpController`, `domain/Document`, `domain/Role`, `domain/User`, `dto/DocumentDto`, `dto/ErpDto`, `dto/RealtimeAlertDto`, `dto/auth/{LoginRequest,LoginResponse,SignupRequest,UserSummary}`, `exception/ErrorCode`, `exception/GlobalExceptionHandler`, `repository/DocumentRepository`, `repository/UserRepository`, `security/CustomUserDetailsService`, `service/AuthService`, `service/DocumentService`, `service/UserService`

- `User.java`: surin는 email 사용(F10). merge V7 email + org_name + approval_status. → **merge 필드셋 유지 + surin의 email 접근 코드 포함**.
- `ErpController`/`ErpDto`: §3-C의 merge ERP와 충돌 검토(아래 ⚠️).

### 3-C. merge 전용 = 보존 (29개, minji의 F5~F8)
> surin 병합 중 **삭제/덮어쓰기 금지**.

config: `AuthTestSeedConfig`, `ErpSeedConfig`, `RagSeedConfig` · controller: `BriefingController`, `DashboardController`, `ErpAdminController`, `PublicController`, `RagController`, `RiskEventController`, `SeverityController` · domain: `ApprovalStatus` · dto: `BriefingDto`, `DashboardDto`, `ErpAdminDto`, `PageResponse`, `RagDto`, `RiskEventDto`, `SeverityDto` · repository: `BriefingRepository`, `DashboardRepository`, `ErpRepository`, `SeverityRepository` · service: `BriefingService`, `DashboardService`, `ErpAdminService`, `ErpService`, `RagService`, `RiskEventService`, `SeverityService`

### ⚠️ 위험구간 2 — ERP 이중 구현
- merge: `ErpSeedConfig` + `ErpService` + `ErpAdminService` + `ErpRepository` (seed·조회·관리)
- surin: `ErpSeedLoaderService` + `ErpExposureRequestService` + `ErpExposureDto`
- 두 세트가 **같은 ERP 마스터를 각자 seed/expose**. 그대로 두면 seed 중복·빈(Bean) 충돌 가능.
- → **merge `ErpSeedConfig`로 단일화, surin `ErpSeedLoaderService` 폐기.** ⚠️ surin `ErpController`가 `erpSeedLoaderService.loadAll()` 수동 시드 엔드포인트를 호출하므로, ErpController 병합 시 **merge 버전 기준으로 두고 surin의 loadAll 엔드포인트는 제외**(merge는 `ErpSeedConfig`가 기동 시 시드 → 수동 트리거 불요).

---

## 4. FastAPI 병합

### 4-A. 순수 신규 = 복붙 (12개)
- api/v1: `realtime_pipeline.py`, `supplier.py`
- models: `extraction_inference.py`, `severity_engine.py`, `triage_filter.py`, `triage_filter.json`, `triage_filter_meta.json`
- schemas: `realtime_pipeline.py`, `supplier.py`
- services: `erp_supplier_assessment_service.py`, `realtime_gdelt_service.py`, `supplier_recommendation_service.py`

### 4-B. `app/main.py` = 라우터 수동 병합 (통째 복붙 금지)
합쳐야 할 최종 라우터 세트(7개):
- 공통: `analyze`, `rag`, `internal`, `documents`, `erpRouter`
- **merge 전용 유지**: `multi_agent_router` ← surin main.py엔 없음
- **surin 추가**: `supplier_router`, `realtime_pipeline_router` ← merge main.py엔 없음

### 4-C. ⚠️ 위험구간 3 — analyze 파이프라인 공유 수정
아래는 양쪽이 동시에 수정 → 수동 병합 필수:
`core/config.py`, `main.py`, `api/dependencies.py`, `api/v1/{documents,internal,rag}.py`, `models/feature_builder.py`, `repositories/{__init__,risk_repository}.py`, `schemas/{analyze,common,document,erp,rag}.py`, `services/{document_service,erp_exposure_service,extraction_service,feature_service,orchestration_service,rag_service,severity_service}.py`, `config/erp_rules.yaml`, `core/exceptions.py`, `requirements.txt`, `tests/*`

- 특히 `severity_service.py`, `extraction_service.py`, `orchestration_service.py`, `feature_service.py`: surin이 F3 실모델(triage/severity_engine/extraction_inference) 연결로 대폭 개편. **surin 버전 우선 + merge의 briefing/severity 연동 훅 보존** 방향 권장.

---

## 5. 설정 / 빌드 파일

| 파일 | 조치 |
|---|---|
| `spring-backend/build.gradle` | `implementation 'org.springframework.boot:spring-boot-starter-mail'` 추가 (F10) |
| `spring-backend/.../application.yml` | surin의 `spring.mail.*`(SMTP), `notification.mail-from`, `historical-data.extra-path`, 업로드 크기, CORS 병합. ⚠️ `historical-data.extra-path`는 로컬 경로(`C:/Users/User/Downloads/...`)라 환경변수로 외부화 권장 |
| `fastapi-ai/requirements.txt` | surin 신규 의존성(ML/triage 관련) diff 후 병합 |
| `.env.example` / `.env` | surin `POSTGRES_URL`(jdbc) 등 키 반영, SMTP·notification 키 추가 |
| `@EnableScheduling` | merge에 이미 존재(조치 불필요). F4 `CollectionService`(30분), F10 `NotificationService`(cron 08:00) 스케줄 동작 확인만 |

---

## 6. 실행 순서 (권장)

1. **백업/브랜치**: backend_merge 현재 브랜치 `badapyobum-minji-integration-v2`. 여기서 진행하거나 `feat/surin-merge`로 분기.
2. **마이그레이션**: §1-3의 V9~V13 작성, surin V6/V7/V10 미반영. → 앱 부팅으로 Flyway 통과 확인.
   - (`Document.java`는 merge의 String 버전 유지 — surin 문서 로직 이식은 §3-B 공유 병합 단계(5)에서 수행.)
3. **build.gradle**: mail 스타터 추가.
4. **Java 신규 31개 복붙**(§3-A, `ErpSeedLoaderService` 제외) → 컴파일.
5. **Java 공유 23개 수동 병합**(§3-B) → 컴파일.
6. **ERP 이중 구현 정리**(§3-C ⚠️).
7. **FastAPI 신규 12개 복붙**(§4-A).
8. **FastAPI main.py 라우터 병합**(§4-B) + analyze 파이프라인 공유 파일 병합(§4-C).
9. **application.yml / requirements / .env 병합**(§5).
10. **검증**(§7).

---

## 7. 검증 체크리스트

- [ ] Flyway: V1~V13 clean DB에서 오류 없이 마이그레이트
- [ ] `analyses`/`raw_events`/`analysis_supplier_recommendations`/`notification_log` 생성 확인
- [ ] `Analysis.java` ↔ V9 컬럼 일치(briefing_id 없음, material_category 있음)
- [ ] `Document.java`=`String` id로 `ddl-auto: validate` 통과(부팅 성공)
- [ ] surin 문서 타입(LTA 등) 적재 시 `ck_document_type`(V13 확장) 통과
- [ ] F9: merge 시드된 `supplier_materials`(approved_status='APPROVED') 기준 추천 후보 반환 확인
- [ ] Spring `./gradlew build` 성공, Bean 중복(특히 ERP seed) 없음
- [ ] FastAPI 기동 + 라우터 7종 등록(`/docs`), analyze 파이프라인 스모크 테스트
- [ ] F3(분석) → F9(추천 저장) → F10(알림 로그) end-to-end 1건
- [ ] merge 기존 기능(Dashboard/Briefing/Severity/Rag/Public/ErpAdmin) 회귀 없음

---

## 8. 결정 사항

> **대원칙: 무손실(no functionality/taxonomy loss).**

1. ✅ **[결정됨] document_type = 합집합 확장(무손실).** merge V1 유지 + V13에서 CHECK를 두 taxonomy 합집합(10종)으로 확장. surin의 LTA 등 그대로 보존. `document_id`는 DB(VARCHAR(40)) 강제로 `Document.java` String 전환 — §2.
2. **[권장] ERP seed = merge `ErpSeedConfig` 단일화.** surin `ErpSeedLoaderService`는 폐기. 무손실 근거: merge 시드가 `supplier_materials`·`purchase_order_items`를 채우고, surin F3(`ErpExposureRequestService`)·F9(`SupplierQualificationService`)가 읽는 전 컬럼이 merge 스키마에 존재함(검증 완료). ⚠️ 단, merge 시드 CSV 데이터셋이 두 기능이 기대하는 행(자재/공급사 목업)의 상위집합인지 확인 — §3-C.
3. **[권장] analyze 파이프라인**: surin 실모델 개편본(triage/severity_engine/extraction_inference)을 base로 + merge 훅 보존 — §4-C.
4. **analyses vs severity_assessments/briefings 의미 중복**: 현 병합은 **별도 유지**(무손실). 장기 통합은 별도 논의.
