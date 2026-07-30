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

# 🎯 최종 통합 실행 계획 (FINAL) — Spring Zone D + FastAPI

> 이 섹션이 **실행 기준(authoritative)**. 아래 §1~§8은 근거·상세. 워크플로우 2회(Spring 공유 23개 / FastAPI 공유 24개) 분석·적대검증 결과 반영.

## 완료됨 ✅
- 마이그레이션 V9~V13 적용·Flyway 검증 (⚠️ **V13 수정 커밋 필요** — `documents`→`contract_documents` 정정본이 아직 미커밋)
- `build.gradle`에 `spring-boot-starter-mail`
- Zone A 신규 파일 복사: Java 31 + FastAPI 12 (`ErpSeedLoaderService` 제외)

## ✅ 실행 결과 (검증 완료 · 2026-07-28)
①~⑧ 전부 적용 + 검증 완료. 아래 검증은 전부 **OpenAI 호출 0**로 수행.

| 단계 | 결과 |
|---|---|
| ① Spring graft 4건 (UserRepository·GlobalExceptionHandler·SecurityConfig·ErpController /exposure) | ✅ 컴파일 성공 |
| ② requirements union (surin ML 스택 추가, openai=1.109.1 유지) | ✅ pip 해소 성공 |
| ③ 스키마 union (FeatureVector/FeatureOverrides + ExtractionRequest 재노출, extraction country_code optional·is_supply_chain_relevant·extraction_model_version) | ✅ |
| ④ erp_exposure take_surin 3파일 (erp_exposure_service·schemas/erp·erp_rules.yaml) | ✅ |
| ⑤ analyze 코어 6파일 (orchestration=merge 골격+surin 2노드(LLM impact_domain·severity_engine), severity_service=evaluate(v1) 보존+score(v0.2), extraction take_surin, feature_service·feature_builder·risk_repository union) | ✅ import OK |
| ⑥ wiring (main.py 라우터 8종=supplier·realtime 추가, dependencies extraction 토글+get_supplier_recommendation_service, config spring_base_url) | ✅ |
| ⑦ rag_doc keep_merge | ✅ 무작업 |
| ⑧ application.yml (spring.mail·management.health.mail.enabled=false·app.historical-data·app.notification) | ✅ |
| ⑨ 검증 | ✅ spring·fastapi healthy / Flyway V1~V13 / FastAPI 전 모듈 import OK / **severity 직접검증 = CRITICAL·83.5·severity-rule-v0.2-realtime·[GDACS_HARD_GATE]** / OpenAI 0건 |

**★ 추가 안전장치**: `CollectionService` 자동수집 `@Scheduled`를 `app.collection.scheduler-enabled`(기본 **false**)로 가드 → 자동 F3→OpenAI 비용 사고 차단(수동 트리거는 유지). 검증: 부팅 60초 경과 후 자동수집 로그 0건.

**무손실 확정**: surin F3/F4/F9/F10 + merge F5~F8(classifier·multi_agent·briefing·rag·erp_context) 모두 보존 — classifier는 `/ml/classify`, severity v1은 `evaluate()`로 공존.

### ✅ 풀 라이브 E2E (2026-07-29 · mock 추출 · OpenAI 0)
실제 Spring→FastAPI→Spring 왕복까지 검증. 로그인(AUTH_TEST_SEED 계정) 후 `POST /api/v1/analyses`(칠레 리튬):
- **F3**: `CRITICAL` / `severity-rule-v0.2-realtime` (surin 검증 공식 실동작)
- **F9**: `material_category=LITHIUM` 추천 3건 — AUS-1001(Pilbara)·KOR-1001(Han River)·CHL-1001(Atacama), surin 문서와 일치
- **F10**: CRITICAL → 4계정 알림 발송(수신자 조회 정상), SMTP 미설정이라 발송만 비활성(정상)

**E2E가 잡아낸 F9 배선 버그 2건 (수정 완료):**
1. **응답 형태** — orchestration을 merge rich 응답으로 바꾸며 top-level `affected_materials`가 누락 → surin `AnalysisService`의 F9 트리거 불발. **수정**: `schemas/analyze.py`의 `AnalyzeResponseData`에 `affected_materials` 필드 복원 + `orchestration_service.py`에서 `affected_materials=extraction.affected_materials`로 채움.
2. **F9 콜백 URL** — FastAPI `supplier_recommendation_service`가 Spring `/suppliers/qualified`로 콜백 시 `spring_base_url` 기본값 `localhost:8080`이 docker에선 fastapi 자신을 가리켜 실패 → 후보 0. **수정**: `docker-compose.yml` fastapi env에 `SPRING_BASE_URL=http://spring:8080` 추가.

> ⚠️ 운영 주의: E2E로 fastapi를 만지는 docker 명령엔 반드시 `OPENAI_API_KEY=` 프리픽스로 mock 강제(안 붙이면 compose가 `.env` 실제 키로 fastapi 재생성 → OpenAI 호출). `.env` 파일 자체는 불변.

**미완/선택**:
- 실제 OpenAI 추출 경로 E2E는 미실행(mock으로 검증 완료). 실추출 시 `affected_materials`가 구체 물질명("lithium carbonate" 등)으로 나와 DB `material_category`(대분류)와 매칭되도록 매핑 보강이 필요할 수 있음 — surin 기존 과제.
- **F4 실시간 자동수집 = 의도된 동작.** `app.collection.scheduler-enabled`(기본 false)로 opt-in — 켜면 GDELT 실시간 트리아지 파이프라인 작동.
- git 커밋: F9 수정 3파일(`schemas/analyze.py`·`orchestration_service.py`·`docker-compose.yml`) 커밋 예정.

## 실행 순서 (의존성 순 — 이대로 진행)

**① Spring Zone D — graft 3개 (저위험)**
- `repository/UserRepository.java` ← `List<User> findByRoleInAndEnabledTrueAndEmailIsNotNull(List<Role>)` + `import Role, java.util.List` (F10 NotificationService가 호출)
- `exception/GlobalExceptionHandler.java` ← `AnalysisNotFoundException` 중첩클래스 + `handleAnalysisNotFound`(404) (F3 AnalysisService가 throw)
- `config/SecurityConfig.java` ← `.requestMatchers("/api/v1/suppliers/qualified").permitAll()` (catch-all 앞) (F9 공개 조회)
- (선택·결정필요) `controller/ErpController.java` ← surin `/exposure` graft 여부. 자동 F9는 이미 동작(AnalysisService→ErpExposureRequestService 직접), `/exposure`는 수동 트리거. `/seed`는 ErpSeedConfig가 대체.
- **나머지 Spring 공유 20개 = 무작업**(merge가 상위집합)

**② FastAPI `requirements.txt` — 부팅 크리티컬, 반드시 먼저 ⚠️**
- union 추가: `pandas · xgboost · requests · newspaper3k · lxml_html_clean · numpy · scikit-learn`
- `openai==1.109.1` 유지(surin 2.45.0 다운그레이드 무손실 — beta.parse는 1.40+ 존재), `chromadb==1.5.9`·`langgraph==1.2.9` 유지
- (안 하면 ⑥의 realtime 라우터 배선 시 `ModuleNotFoundError`로 앱 전체 부팅 실패)

**③ FastAPI 스키마 union — ⑤ analyze 선행조건**
- `schemas/analyze.py`: `FeatureVector`+`FeatureOverrides`에 surin(tone_score·is_supply_chain_relevant·bdi_index)+merge(rainfall_24h_mm·actor1_type·actor2_type) **전 필드 기본값** 부여. `ExtractionRequest` 재노출.
- `schemas/extraction.py`: `country_code` optional화 + `is_supply_chain_relevant`·`extraction_model_version` 추가.
- `schemas/common.py` = keep_merge(무작업, Severity.UNKNOWN 유지)

**④ FastAPI erp_exposure — take_surin (3파일 동반 이동 필수, F9)**
- `services/erp_exposure_service.py` + `schemas/erp.py` + `config/erp_rules.yaml` → surin본 채택(surin 상위집합). (하나만 이동 시 ImportError/KeyError)

**⑤ FastAPI analyze 코어 — 수동 병합 (실제 핵심 작업)**
- `services/extraction_service.py` → surin 주입형 채택(extraction_inference 위임)
- `services/severity_service.py` → merge 2-인자 `score` 제거, surin 1-인자 `score(features)`→`severity_engine` 추가, **`evaluate()` 유지**(internal.py·ERP용), `SeverityResult.mock=False` 명시
- `services/feature_service.py`·`models/feature_builder.py`·`repositories/risk_repository.py` → union
- `services/orchestration_service.py` → **merge 다단계 골격+rich 응답 유지 + 노드 2개만 surin으로 교체**(impact_domain=LLM draft, severity=severity_engine) + tone/relevance 주입 + `remember()` 호출

**⑥ FastAPI wiring**
- `api/dependencies.py` → merge provider 유지 + `get_supplier_recommendation_service` 추가 + `get_orchestration_service`를 surin 5-인자로 **재작성** + `get_extraction_service` OpenAI 토글
- `main.py` → merge(multi_agent·/health(vector_store)·validation) 유지 + `supplier_router`·`realtime_pipeline_router` 추가
- `core/config.py` → `spring_base_url` 필드 추가(F9가 Spring URL 조립에 사용) / `core/exceptions.py`·`repositories/__init__.py` = 무작업

**⑦ FastAPI rag_doc = keep_merge (7파일 무작업)**
- documents/internal/rag + rag_service/document_service + schemas/document·rag → merge 유지
- ⚠️ `internal.py /severity/score`는 merge v1(evaluate) 유지 → **/analyze=surin v0.2, internal=merge v1 두 severity 용도별 공존**

**⑧ 설정**
- `spring-backend/.../application.yml`: surin `spring.mail.*`·`notification.mail-from`·`historical-data.extra-path`·CORS 병합(로컬경로는 env 외부화) / `.env` 키 추가

**⑨ 검증**
- 컴파일(`./gradlew build`) + 부팅(docker `--build`) + Flyway V1~V13
- **E2E 무손실 증명**: 칠레 리튬 폭우 입력 → `CRITICAL`/severity 100/reason_codes 3개/공급사 추천 1·2·3위가 surin 문서 예시와 일치
- 회귀: merge F5~F8(Dashboard/Briefing/Severity/Rag/ErpAdmin) + F10 메일(Mailtrap)

## FastAPI 클러스터 판정 (근거)
| 클러스터 | 판정 | 전략 |
|---|---|---|
| analyze | ⚠️ NEEDS_FIX | merge 골격 + surin 노드 2개 (수동) — ③⑤⑥ |
| erp_exposure | ✅ OK | take_surin 3파일 — ④ |
| wiring | ⚠️ NEEDS_FIX | union, 단 ② 선행 필수 — ⑥ |
| rag_doc | ✅ OK | keep_merge 7파일 — ⑦ |
| deps | ✅ OK | union, openai 1.109.1 — ② |

## 설계 결정 (기록)
1. **impact_domain**: `/analyze`는 **surin LLM 추출** 채택(merge classifier는 규칙목+휴면 스텁). merge classifier는 `/ml/classify`(internal)로 보존 → 실제 XGBoost 확보 시 토글.
2. **severity**: `/analyze`는 **surin `severity_engine`(v0.2-realtime, 4,081건 검증)**. merge `evaluate()`(v1, ERP기반)는 internal·ERP 경로용으로 보존 → **두 엔진 공존, 무손실**.

## ⚠️ 적대검증이 잡은 "그냥 두면 터지는" 것 (③⑤⑥에 포함)
- requirements 누락 → 부팅 실패(②) / `country_code` 필수 → ValidationError(③) / `FeatureOverrides` union 누락 → AttributeError(③) / `SeverityService.score()` 시그니처 충돌 → TypeError(⑤) / `get_orchestration_service` arity 불일치 → TypeError(⑥) / `ExtractionRequest` 미노출 → ImportError(③)

## 별도 검증 항목 (코드 밖)
- ~~**ERP 시드 데이터**: merge `ErpSeedConfig` CSV가 F9 기대 공급사/자재의 상위집합인지 확인~~ → ✅ **확인 완료**: surin(`seed-data/erp`) vs merge(`data/ERP_data/spring-csv`) 11개 CSV(00~10) **바이트 단위 동일**. F9 데이터 무손실 확정.
- **pip resolve dry-run**: `chromadb`↔`numpy` 핀 충돌 여부
- **auth 동작 변화 인지**: merge 가입 승인 게이트(신규→PENDING) — 의도된 변경(surin C2와 다름)

## 무손실 보장 요약
surin **F3(검증 severity·LLM 분류·추출)·F4·F9·F10 전부 보존**, merge **F5~F8·classifier·multi_agent 전부 보존** — 양쪽 엔진이 용도별 공존. 단 자동이 아니라 ①~⑥의 union·수동병합을 정확히 수행해야 성립.

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
