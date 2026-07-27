# 백엔드·AI 파이프라인 구현 결과 정리

> 이 문서의 모든 내용은 실제 소스 코드와 1:1로 대조해 검증했다. 기준 커밋은 `e728fbd`(2026-07-24)다.
> 코드를 고치면 이 문서도 같이 고쳐야 한다. 특히 6절(코드 지도)과 3절(API 목록)이 먼저 낡는다.

## 0. 이 문서를 읽는 순서

| 목적 | 읽을 절 |
| --- | --- |
| 프로젝트를 처음 본다 | 1 → 2 → 5 |
| 코드를 이어받아 개발한다 | 3(API) → 6(코드 지도) → 7(DB) → 10(남은 작업) |
| 지금 어디까지 됐는지만 알면 된다 | 2 → 8 → 9 |
| 발표·보고 자료를 만든다 | 2 → 8 → 11 |

단계 흐름을 그림으로 보려면 [s01-s16-flow.html](s01-s16-flow.html)을 브라우저로 열면 된다.

## 1. 전체 구조와 책임 경계

```text
React
  ↓ (모든 요청은 Spring을 통과한다)
Spring Boot          업무 데이터·인증·오케스트레이션·PostgreSQL 읽기·쓰기
  ↓ (내부 호출)
FastAPI              문서 처리·벡터·규칙 계산·템플릿 조립
  ↓
ChromaDB             계약서 청크 임베딩 저장·검색
```

이 경계는 코드로 강제되어 있다.

- React에는 FastAPI 주소가 없다. `frontend/src/documentApi.js`는 Spring 주소(`VITE_SPRING_API_BASE_URL`)만 사용한다.
- FastAPI는 PostgreSQL에 접근하지 않는다. 필요한 업무 데이터는 Spring이 요청 본문에 담아 전달한다.
- Spring은 ChromaDB에 접근하지 않는다. 벡터 검색은 항상 FastAPI를 거친다.

### Mock으로 되어 있는 것과 아닌 것

실제 LLM·XGBoost·OpenAI Embedding은 아직 연동 조건이 준비되지 않아 Mock·규칙 기반 구현을 사용한다. 이들은 16단계에서 교체할 대상이다.

> **ERP 데이터는 교체 대상이 아니다.** ERP 데이터(`data/ERP_data/spring-csv`)는 회사 보안 정책상 실제 사내 ERP 시스템에 직접 연결할 수 없어, 실제 ERP 연동으로 교체할 계획이 없는 이 프로젝트의 최종 데이터 소스다. 응답의 `data_source: "ERP_MOCK"`, `mock: true` 필드는 "실시간 ERP 피드가 아님"을 나타낼 뿐, 16단계 이후 교체될 임시 표시가 아니다. 이 데이터를 갱신·관리하는 기능은 일회성 개발 편의가 아니라 영구적으로 유지할 운영 기능으로 설계해야 한다.

## 2. 지금 동작하는 것

| 기능 | 구현 범위 | 상태 |
| --- | --- | --- |
| C1 문서 업로드 | 파일 검증, 원본 저장, 상태 관리, FastAPI 처리, Chroma 적재, 상태 조회, 재처리 | Mock Embedding 기준 완료 |
| C2 인증 | 회원가입·로그인·JWT·Refresh·Logout·내 정보·가입 승인 | 완료 |
| C3 상태 관리 | `PENDING → PROCESSING → COMPLETED/FAILED` | 완료 |
| C5/D1 중복 방지 | 계약별 SHA-256 중복 차단, 재적재 시 기존 청크 교체 | 완료 |
| C6 관측성 | Spring·PostgreSQL Health, Chroma Health | 최소 범위 완료 |
| F1 ERP 영향 | ERP Context 조회, 가용재고·재고일수·안전재고·의존도·공급 공백 계산 | 완료 |
| F2 RAG | 조항·문단 청킹, ChromaDB, Metadata Filter, 근거 청크 검색 | 완료 |
| F3/F8 Severity | 규칙 기반 NORMAL/WARNING/CRITICAL/UNKNOWN, FEOC Hard Gate | 완료 |
| F5 브리핑 | ERP·Severity·RAG·대체공급사를 모은 템플릿 브리핑 생성·저장·조회 | 완료 |
| F6 Master Data | 자재·공급사·계약·창고·발주·입고 스키마와 Seed, ERP 데이터 갱신 API 10종 | 완료 |
| F9 대체 공급사 | 인증·FEOC·승인상태 기준 적격 후보 조회 | 브리핑 연계 범위 완료 |
| F7 근거 계보 | 근거 저장·조회, 브리핑↔Severity 판정 링크, 전용 계보 조회 API | 완료 (뉴스 원문 계보는 F4 대기) |
| M5 근거 | 검색 유사도, 페이지·청크·Embedding 버전, `rule_version`·`template_version` | 완료 |
| F11 대시보드 | Dashboard 요약·목록 조회 API(Dashboard·Risk·Contract·Briefing) | 백엔드 완료, React 화면 미구현 |
| F4 외부 데이터 수집 | 미구현 (실시간 알림 API는 고정 Mock 응답) | 미구현 |

### "완료"라는 표현의 범위

`완료`는 현재 계획한 Mock·규칙 기반 범위의 완료를 뜻한다. 실제 OpenAI Embedding과 LLM 계약 해석까지 끝났다는 의미가 아니다. 검증 수준은 8절에 따로 정리했다.

### 2.1 F/C/M/D 기능별 구현 현황

기능 ID(F/C/M/D) 기준으로 코드 대조한 현황이다. 상태는 4가지로 구분한다.

- ✅ **구현됨** — Mock·규칙 기반이라도 실제로 동작
- 🟡 **부분 구현** — 일부만 동작하거나, 실제 모델/프론트 연동이 남음
- ❌ **미구현** — 코드가 비어 있음(대부분 외부 재료 대기)
- ⬜ **범위 제외** — 못 한 게 아니라, 의도적으로 안 하기로 결정

#### F — 업무 기능

| 기능 | 상태 | 범위 / 남은 것 | 담당/대기처 |
| --- | --- | --- | --- |
| F1 ERP 영향 | 🟡 부분 | Spring 결정적 수치 계산 ✅ / FastAPI 자연어 해석은 **템플릿**(LLM 미연동) | LLM 연동 시 |
| F2 RAG | ✅ 구현 | 업로드→청킹→검색 E2E(Mock Embedding), 계약 대응분석 템플릿까지 | — |
| **F3 AI 분석** | 🟡 부분 | Severity 규칙엔진 ✅ / Extraction·XGBoost는 **Mock**(실제 모델 대기) | **🔗 타인 코드 병합 예정** |
| **F4 수집** | ❌ 미구현 | Scheduler·외부 수집 없음. 실시간 알림 API는 고정 Mock | **🔗 타인 코드 병합 예정** |
| F5 브리핑 | ✅ 구현 | 템플릿 브리핑 생성·저장·조회 | — |
| F6 Master Data | ✅ 구현(최소) | 스키마+ERP Seed+갱신 API 10종. Alias·자동매칭 미구현 | — |
| F7 근거 계보 | ✅ 구현 | 저장·조회 + 브리핑↔Severity 판정 링크(`assessment_id`, V8) + 전용 계보 조회 API(`GET /briefings/{id}/lineage`). 뉴스 원문 계보는 F4 대기 | — |
| F8 규제 Hard Gate | ✅ 구현 | FEOC Hard Gate가 Severity 엔진에 반영 | — |
| **F9 대체 공급사** | ✅ 구현 | 브리핑 연계 범위(적격 필터) | **🔗 타인 코드 병합 예정** |
| F10 알림 | ❌ 미구현 | 실제 발송(이메일 등) 없음 | — |
| F11 대시보드 | 🟡 부분 | 백엔드 조회·집계 API ✅ / React 화면 미연동, 지도 알림은 Mock(F4 의존) | 프론트 팀 |
| F12 업무 추적 | ⬜ 범위 제외 | Task 관리 영역. 핵심 파이프라인과 무관 | — |

> **🔗 미통합 기능 (타인 코드 병합 예정)** — **F3(AI 분석)·F4(수집)·F9(대체 공급사)** 는 아직 통합되지 않았으며, 다른 팀원의 코드를 받아와 합칠 예정이다.
> - **F3 AI 분석** — Extraction·XGBoost 실제 모델부(현재 Mock) 병합 대기
> - **F4 수집** — 외부 데이터 수집/Scheduler 코드 병합 대기
> - **F9 대체 공급사** — 별도 구현 코드 병합 예정

#### C — 공통 기반

| 기능 | 상태 | 범위 / 남은 것 |
| --- | --- | --- |
| C1 업로드 | ✅ 구현 | 검증·저장·상태·재처리 |
| C2 인증 | ✅ 구현 | 회원가입·로그인·JWT·Refresh·RBAC |
| C3 작업·재시도 | ✅ 구현(최소) | `COMPLETED/FAILED` 상태. 자동 재시도는 범위 제외 |
| C4 감사 로그 | ⬜ 범위 제외 | 기본 앱 로그로 대체. 컴플라이언스 요건 없어 생략 |
| C5 중복 방지 | ✅ 구현 | SHA-256 + Unique Constraint (D1과 동일 메커니즘) |
| C6 관측성 | ✅ 구현(최소) | Health 4종 + 장애검증. Trace ID·단계별 시간·마지막성공시각은 없음 |
| C7 LLM 검증 | 🟡 부분 | 스키마 검증 골격만. 실제 LLM(Claude 브리핑) 연동 시 본격 필요 |

#### M — 모델링 (원칙적으로 모델링 팀 로컬 작업)

| 기능 | 상태 | 범위 / 남은 것 |
| --- | --- | --- |
| M1 라벨링 | ⬜ 범위 제외 | 모델링 팀 |
| M2 Dataset 버전 | ⬜ 범위 제외 | 모델링 팀 |
| M3 Registry | ❌ 미구현 | Artifact 로딩 규격만 예정. **XGBoost 모델 전달 대기** |
| M4 학습 검증 | ⬜ 범위 제외 | 모델링 팀 |
| M5 설명 가능성 | ✅ 구현(최소) | confidence·reason_codes·calculation_details 반환. 실시간 SHAP은 팀 몫 |
| M6 모니터링 | ⬜ 범위 제외 | Drift·성능추적 미구현 |

#### D — 데이터 처리

| 기능 | 상태 | 범위 / 남은 것 |
| --- | --- | --- |
| D1 중복 제거 | ✅ 구현 | C5와 동일 메커니즘(URL/Hash/Unique) |
| D2 소스 신뢰도 | ⬜ 범위 제외 | 등급화 삭제, 출처만 저장(수집 F4 없어 실적용 X) |
| D3 품질 검증 | ✅ 구현 | 서비스 경계 타입·범위·Enum·FK 검증(DTO/Pydantic). **LLM 무관, 비용 0** |
| D4 단위·통화 | ⬜ 범위 제외 | 변환 코드 없음. ERP_MOCK 단일 소스라 단위 일관, 스펙 고정만 |

집계: ✅ 구현 14 · 🟡 부분 4 · ❌ 미구현 3 · ⬜ 범위 제외 8

- **뼈대(C1·F2·F1·F8·F5)는 Mock 기준으로 완결** — RAG 업로드→검색→ERP 계산→Severity→브리핑이 실제로 흐른다.
- **❌ 3개는 외부 재료 대기** — F4·F10은 신규 개발 대상, M3은 팀 XGBoost 모델 대기(F3와 한 묶음).
- **⬜ 8개는 의도적 제외** — 빼도 프로젝트 완결에 지장 없음. "미구현"과 구분한다.

## 3. API 전체 목록

### 3.1 Spring Boot — React가 호출하는 외부 API

인증이 필요 없는 것은 `/auth/login`, `/auth/signup`, `/auth/refresh`, Swagger, `/actuator/health`뿐이다. 나머지 `/api/v1/**`는 전부 Bearer Token이 필요하다(`SecurityConfig.java:104`).

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 회원가입 (승인 대기 상태로 생성) |
| POST | `/api/v1/auth/login` | 로그인, Access·Refresh Token 발급 |
| POST | `/api/v1/auth/refresh` | Access Token 재발급 |
| POST | `/api/v1/auth/logout` | Token 폐기 |
| GET | `/api/v1/auth/me` | 내 정보 조회 |
| POST | `/api/v1/auth/users/{userId}/approve` | 가입 승인 |
| POST | `/api/v1/documents` | 문서 업로드 (Multipart) |
| GET | `/api/v1/documents/{document_id}` | 문서 처리 상태 조회 |
| POST | `/api/v1/documents/{document_id}/reprocess` | 저장된 원본으로 재적재 |
| POST | `/api/v1/rag/search` | 계약 근거 청크 검색 |
| POST | `/api/v1/erp/context` | ERP 영향 수치 계산 |
| POST | `/api/v1/severity/assessments` | 위험 등급 산정·저장 |
| GET | `/api/v1/severity/assessments/{assessmentId}` | 위험 등급 조회 |
| POST | `/api/v1/briefings` | 브리핑 생성·저장 |
| GET | `/api/v1/briefings` | 브리핑 목록 |
| GET | `/api/v1/briefings/{briefingId}` | 브리핑 조회 |
| GET | `/api/v1/briefings/{briefingId}/lineage` | 브리핑 근거 계보 조회 (F7: 결론→Severity 판정·ERP 스냅샷→계약 근거→버전) |
| GET | `/api/v1/risk-events` | 리스크 이벤트 목록 (S14, 프론트 `RiskEvent` 계약. 데이터는 F3/F4 모델 배선 전까지 placeholder) |
| GET | `/api/v1/dashboard/summary` | 대시보드 요약 (S14) |
| GET | `/api/v1/dashboard/materials` | 자재별 현황 (S14) |
| GET | `/api/v1/dashboard/import-dependency` | 수입 의존도 집계 (S14) |
| GET | `/api/v1/contracts` | 계약 목록 (S14, 단건 조회는 없음) |
| GET | `/api/v1/map/realtime-alerts` | 실시간 알림 (고정 Mock 응답) |
| GET | `/actuator/health`, `/actuator/info` | 시스템 정상 여부 모니터링. **컨트롤러 없음** — Spring Boot Actuator 자동 제공. 설정: `application.yml`, 접근 허용: `SecurityConfig.java` (인증 불필요) |

ERP 데이터 갱신 API는 `/api/v1/erp/admin` 아래 10개다. 모두 `POST`이며 ERP 문자열 ID 기준 단건 Upsert다.

```text
/materials              /suppliers              /warehouses
/contracts              /supplier-materials     /inventory-snapshots
/material-consumptions  /purchase-orders        /purchase-order-items
/goods-receipts
```

### 3.2 FastAPI — Spring만 호출하는 내부 API

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/v1/documents/process` | 문서 추출·청킹·임베딩·Chroma 적재 |
| POST | `/api/v1/rag/search` | Metadata Filter + 벡터 유사도 검색 |
| POST | `/api/v1/rag/contracts` | FastAPI 단독 검증용 계약서 업로드 |
| POST | `/api/v1/internal/severity/score` | 규칙 기반 위험 등급 계산 |
| POST | `/api/v1/internal/briefings/compose` | 템플릿 브리핑 조립 (13단계 본선) |
| POST | `/api/v1/internal/llm/extract` | LLM 정보 추출 (Mock) |
| POST | `/api/v1/internal/ml/classify` | XGBoost 영향 도메인 분류 (Mock) |
| POST | `/api/v1/internal/briefings` | 구 Orchestration 호환용 브리핑 (Mock) |
| POST | `/api/v1/analyze` | 뉴스 이벤트 통합 분석 (Mock, Spring 연동 전 경로) |
| GET | `/health` | FastAPI·Chroma Health |

마지막 네 개 중 `analyze`·`briefings`·`llm/extract`·`ml/classify`는 초기에 만든 Mock 경로이고, Spring이 실제로 호출하는 것은 `documents/process`·`rag/search`·`severity/score`·`briefings/compose`다.

### 3.3 컨트롤러별 집계

Spring 컨트롤러 9개에서 추출한 외부 API는 32개다. `/actuator/**`는 우리가 짠 `@RestController`가 아니라 Spring Boot Actuator가 자동 제공하므로 컨트롤러 집계와 분리한다.

| 컨트롤러 | class 경로 | 개수 |
| --- | --- | --- |
| `AuthController` | `/api/v1/auth` | 6 |
| `DocumentController` | `/api/v1/documents` | 3 |
| `DashboardController` | `/api/v1` (dashboard·contracts) | 4 |
| `BriefingController` | `/api/v1/briefings` | 4 |
| `RagController` | `/api/v1/rag` | 1 |
| `ErpController` | `/api/v1/erp` | 1 |
| `SeverityController` | `/api/v1/severity/assessments` | 2 |
| `RealtimeAlertController` | `/api/v1/map` | 1 (고정 Mock) |
| `ErpAdminController` | `/api/v1/erp/admin` | 10 |
| **컨트롤러 합계** | **9개 컨트롤러** | **32** |
| (참고) Actuator | `/actuator/health`, `/actuator/info` | 2 (프레임워크 제공) |

- Spring 합계: 외부 32 + Actuator 2 = **34**
- FastAPI 내부 API: **10** (내부 5 + analyze·documents/process·rag 2종·health)

## 4. 실제 호출 흐름

### 4.1 문서 업로드

```text
1. React 또는 Swagger에서 PDF/TXT/CSV 선택
2. Spring POST /api/v1/documents
3. Spring이 확장자·MIME·크기(50MB)·문서유형·계약/공급사/자재 존재 여부 검증
4. Spring이 {prefix}_{UUID} 형식 document_id 발급
5. uploads/contracts/{document_id}/original.* 원본 저장
6. PostgreSQL contract_documents에 PENDING 저장
7. 상태를 PROCESSING으로 변경
8. FastAPI POST /api/v1/documents/process 호출
9. FastAPI가 PDF/TXT/CSV 텍스트 추출 (CSV는 TXT와 동일하게 UTF-8 처리)
10. 조항 → 문단 → 길이 순서로 청킹
11. Mock Embedding 생성
12. ChromaDB에 청크와 Metadata 저장
13. FastAPI가 청크 수와 Embedding 정보 반환
14. Spring이 상태를 COMPLETED로 변경
15. 오류 시 FAILED와 오류 코드 저장
```

### 4.2 계약 근거 검색

```text
Spring POST /api/v1/rag/search
→ contract_id 또는 supplier_id 필수 검증
→ FastAPI POST /api/v1/rag/search
→ Chroma Metadata Hard Filter
→ 필터 범위 안에서만 벡터 유사도 검색
→ 원문·페이지·청크·유사도 반환
```

Metadata Filter를 먼저 적용하므로, 다른 계약서의 조항이 근거로 섞여 나오지 않는다.

### 4.3 ERP 영향 계산

```text
Spring POST /api/v1/erp/context
→ ERP 자재 ID를 내부 material_id로 매핑
→ 재고·사용량·계약·공급사·발주 조회
→ 가용재고·재고일수·공급 공백 계산
→ 데이터 품질 상태와 함께 ERP Context 반환
```

### 4.4 브리핑 생성 (12·13단계)

```text
Spring POST /api/v1/briefings
→ ErpService.buildContext()                    ERP 수치
→ FastAPI /internal/severity/score             위험 등급
→ RagService.search()                          계약 근거
→ ErpRepository.findEligibleAlternativeSuppliers()  적격 대체 공급사 (최대 5곳)
→ FastAPI /internal/briefings/compose          템플릿 조립
→ briefings 테이블 저장
```

Spring이 재료를 모으고 FastAPI가 문장으로 조립하는 구조다. 자격 미달 공급사는 Spring이 걸러내고 적격 후보만 FastAPI에 넘긴다.

## 5. 12·13단계를 하나로 합친 이유

원래 계획은 12단계 대응 분석과 13단계 브리핑을 나누는 것이었으나, 두 결과가 항상 같은 자재·공급사 단위로 함께 조회되므로 엔드포인트 하나로 합쳤다.

### LLM 없이 문장을 만드는 방법

핵심은 AI가 새로 판단하게 하지 않고, 이미 계산된 값을 고정된 문장 틀에 채우는 것이다.

```python
if r.inventory_days < r.safety_stock_days:
    text += f" 안전재고 기준({r.safety_stock_days:g}일) 미만이라 주의가 필요합니다."
```

규칙으로 문장을 고르고 값만 채우므로 동일 입력은 항상 동일 브리핑을 만든다. 16단계에서는 `briefing_service.py`의 `_inventory_summary()` 같은 섹션 함수만 LLM 호출로 바꾸면 된다.

지켜야 할 원칙이 두 가지 있다.

- 계약 근거는 항상 "담당자 검토가 필요합니다"로만 제시한다. "계약 위반 확정" 같은 새 판단은 만들지 않는다.
- 근거가 없으면 `근거 부족 — 담당자 확인 필요`로 정직하게 표시한다. 재고 관점(숫자)과 계약 관점(문서 근거)은 하나의 결론으로 합치지 않고 각각 별도 섹션으로 남긴다.

## 6. 코드 지도

### 6.1 Spring Boot

| 파일 | 책임 |
| --- | --- |
| `controller/*.java` | HTTP 입출력만 담당. 업무 로직 없음 |
| `service/DocumentService.java` | 업로드 전체 흐름 관리 |
| `service/ErpService.java` | ERP 수치 계산 (결정적 수식) |
| `service/SeverityService.java` | FastAPI 규칙 엔진 호출·검증·저장 |
| `service/BriefingService.java` | 브리핑 오케스트레이션 (재료 수집 → 조립 → 저장) |
| `service/RagService.java` | FastAPI 검색 호출 |
| `service/ErpAdminService.java` | ERP 데이터 10종 Upsert |
| `service/AuthService.java` | 로그인·Refresh·Logout·내 정보 |
| `service/RealtimeAlertService.java` | 고정 Mock 알림 응답 |
| `repository/*.java` | SQL 조회. `ErpRepository`가 가장 크다 |

#### `DocumentService.java`

| 함수 | 동작 |
| --- | --- |
| `upload()` | 검증 → Hash → 중복 확인 → 원본 저장 → PENDING/PROCESSING → FastAPI 호출 → COMPLETED/FAILED |
| `validate()` | 파일, MIME, 크기, 문서 유형, 계약·공급사·자재 존재 여부 검사 |
| `sanitizeFileName()` | `../` 같은 경로 문자열 제거 |
| `sha256()` | 같은 계약 안의 중복 문서 판단용 Hash |
| `processWithFastApi()` | 파일·Metadata를 FastAPI로 전달하고 응답 ID 일치 확인 |
| `reprocess()` | 저장된 원본을 다시 읽어 `force_reprocess=true`로 적재 |

업로드 최대 크기는 `app.upload.max-file-size` 설정값이며 기본 52428800(50MB)이다. 허용 확장자는 PDF·TXT·CSV다.

#### `ErpService.java`

계산식은 전부 결정적이다. 분모가 0이거나 값이 없으면 `null`을 반환하고, 그 상태가 데이터 품질 상태(`VALID`/`INCOMPLETE`/`STALE`)로 표현된다.

```text
available_quantity
= on_hand - reserved - blocked - quality_hold

inventory_days
= available_quantity / average_daily_usage

safety_stock_shortage_quantity
= max(safety_stock_quantity - available_quantity, 0)

expected_supply_gap_days
= max(next_eta_days - inventory_days, 0)
```

#### `ErpRepository.java`

| 함수 | 조회 내용 |
| --- | --- |
| `findMaterial()` | ERP 자재 ID와 내부 ID 매핑 |
| `aggregateCurrentInventory()` | 최신 재고와 안전재고 집계 |
| `aggregateCurrentConsumption()` | 평균 일 사용량 |
| `findSupply()` | 공급사·계약·의존도·FEOC |
| `findNextInbound()` | 가장 빠른 유효 입고 예정일 |
| `sumRemainingQuantity()` | 발주량 − 입고량 |
| `findAlternativeSupplierStatus()` | 다른 승인 공급사 존재 여부 |
| `findEligibleAlternativeSuppliers()` | 인증·FEOC·승인상태를 만족하는 적격 후보 목록 (F9) |

#### `BriefingService.java`

`generate()`가 4.4절 순서를 그대로 실행한다. FastAPI 응답은 그대로 믿지 않고 검증한다. 등급이 정해진 4개 값 중 하나인지, 점수가 0~100인지, `reason_codes`와 `rule_version`이 비어 있지 않은지 확인하고, 어긋나면 `INVALID_SEVERITY_RESPONSE`로 실패시킨다. 브리핑 조립 응답도 `headline`·`template_version` 존재 여부를 같은 방식으로 검사한다.

### 6.2 FastAPI

#### `services/document_service.py`

| 함수 | 동작 |
| --- | --- |
| `process()` | Hash → 추출 → 청킹 → 청크 생성 → Vector Store 적재 |
| `_extract_pages()` | TXT/CSV UTF-8 디코딩 또는 PDF 페이지별 추출 |
| `_chunk_pages()` | 페이지 번호를 유지하며 청킹 |
| `_split_clause_sections()` | `제1조`, `Article 1` 같은 조항 경계 우선 탐색 |
| `_split_section()` | 긴 조항을 문단 단위로 분리 |
| `_split_long_text()` | 긴 문단만 크기·overlap 기준으로 분리 |

조항 → 문단 → 길이 순서로 자르기 때문에, 한 조항이 여러 청크로 쪼개져 근거가 잘리는 경우를 줄인다. 기본값은 `chunk_size=900`, `chunk_overlap=120`이다.

같은 파일의 `InMemoryDocumentStore`는 단위 테스트용 대체 구현이다. ChromaDB가 연결된 실제 경로에서는 영속 저장소로 쓰지 않는다.

#### `services/embedding_service.py`

교체 계약이 이미 분리되어 있다.

```text
EmbeddingProvider (Protocol)     ← 계약: embed_documents(), embed_query()
├─ MockEmbedding                 ← 현재 기본값
└─ (OpenAI 구현체)               ← 16단계에 주입할 자리, 아직 없음
```

`get_embedding_provider()`가 `EMBEDDING_PROVIDER` 설정(`mock` 또는 `openai`)에 따라 구현을 고른다. `openai`로 설정했는데 구현체를 주입하지 않으면 `ModelUnavailable`이 발생한다. 즉 16단계 작업은 **계약을 만드는 일이 아니라 계약을 만족하는 클래스 하나를 주입하는 일**이다.

Mock은 토큰 분리 → SHA-256으로 위치 계산 → 빈도 누적 → 정규화 방식이고, 외부 호출이 없으므로 항상 같은 벡터를 만든다. 기본 차원은 1536, 버전은 `mock-v1`이다.

#### `services/vector_store_service.py`

| 함수 | 동작 |
| --- | --- |
| `health_check()` | Chroma와 Collection 접근 확인 |
| `upsert_chunks()` | 필수 필드 검증, 같은 문서 기존 청크 삭제 후 저장 |
| `search()` | Metadata Filter를 먼저 적용하고 벡터 검색 |
| `get_document()` / `delete_document()` | 문서 단위 조회·삭제 |
| `_vector_id()` | `{document_id}:{chunk_index}` 형식 ID |
| `_cosine_similarity()` | Chroma 거리 값을 화면용 유사도로 변환 |

#### `services/severity_service.py`

FEOC → 데이터 품질 → 입력 부족 → 점수 합산 순서로 판정한다. 앞의 조건에 걸리면 뒤는 계산하지 않는다.

```text
feoc_status == YES        → CRITICAL 100점 고정 (FEOC_HARD_GATE)
data_quality == INVALID   → UNKNOWN
사용 가능한 입력 0개       → UNKNOWN
그 외                     → 6개 항목 점수 합산 (최대 100)
```

6개 항목은 재고(최대 35), 공급 공백(20), 공급사 의존도(20), 가격 변동(15), 물류 지연(20), GDACS 경보(40)다. 합계 70 이상이면 CRITICAL, 30 이상이면 WARNING, 그 미만은 NORMAL이다. 각 항목은 왜 그 점수가 나왔는지를 `reason_codes`로 남긴다.

#### `services/briefing_service.py`

`compose()`가 섹션별 함수를 호출해 브리핑을 조립한다. 각 함수는 값이 없을 때 무엇을 쓸지가 정해져 있다.

| 함수 | 값이 없을 때 |
| --- | --- |
| `_inventory_summary()` | "재고 소진 일수를 계산할 수 없습니다 (UNKNOWN)" |
| `_supply_gap_summary()` | "확인된 입고 예정(ETA)이 없습니다" |
| `_contract_evidence_summary()` | "근거 부족 — 담당자 확인 필요" |
| `_alternative_supplier_summary()` | "승인된 대체 공급사 후보가 없습니다" |
| `_warnings()` | 데이터 품질이 `VALID`가 아니거나 Mock이면 경고 자동 추가 |

### 6.3 React 검증 화면

`frontend/`는 정식 프론트엔드가 아니라 백엔드 E2E 검증용 클라이언트다.

| 함수 | 동작 |
| --- | --- |
| `App.jsx` `submit()` | 파일 검증, 토큰 확인, 업로드, 진행 상태 표시 |
| `App.jsx` `loadStatus()` | 상태 조회. 처리 중이면 1.5초 간격 재조회 |
| `App.jsx` `applyDocument()` | 응답을 화면에 반영하고 마지막 문서 ID 저장 |
| `documentApi.js` `validateDocumentFile()` | PDF/TXT/CSV 확장자, MIME 일치, 빈 파일, 50MB 제한 검사 |
| `documentApi.js` `uploadDocument()` | Bearer Token을 붙여 Spring Multipart 호출, 진행률 제공 |
| `documentApi.js` `getDocumentStatus()` | Spring 상태 조회 |

## 7. 데이터베이스 스키마

Flyway 마이그레이션은 순서대로 적용된다. `R__`은 반복 적용 가능한 Seed다.

| 파일 | 만드는 것 |
| --- | --- |
| `V1__create_master_and_document_schema.sql` | `materials`, `suppliers`, `contracts`, `contract_documents` |
| `V2__create_auth_tables.sql` | `users` |
| `V3__create_revoked_token_sessions.sql` | `revoked_token_sessions` (Logout된 Token) |
| `V4__extend_erp_master_and_operations.sql` | `warehouses`, `supplier_materials`, `inventory_snapshots`, `material_consumptions`, `purchase_orders`, `purchase_order_items`, `goods_receipts` |
| `V5__create_severity_assessments.sql` | `severity_assessments` |
| `V6__create_briefings.sql` | `briefings` (JSONB 컬럼 5개) |
| `V7__extend_users_for_frontend_auth.sql` | `users`에 승인 상태 컬럼 추가 |
| `V8__link_briefing_lineage.sql` | `briefings.assessment_id` 추가 (F7 근거 계보: Severity 판정 FK, NULLABLE) |
| `R__insert_c1_reference_seed.sql` | 업로드·검색 E2E용 최소 자재·공급사·계약 |

두 가지 설계 원칙이 있다.

- ERP 문자열 ID(`MAT-LI-CARB` 등)와 PostgreSQL 내부 숫자 PK를 분리한다. 외부 시스템 ID가 바뀌어도 내부 관계가 깨지지 않는다.
- PK·FK·Unique·Check Constraint를 DB에도 건다. 잘못된 참조와 중복은 애플리케이션 코드가 아니라 DB에서도 막힌다.

## 8. 검증 상태

### 자동 테스트

| 위치 | 파일 |
| --- | --- |
| Spring (9개) | `AuthFlowTest`, `DocumentControllerTest`, `DocumentServiceTest`, `ErpFeatureTest`, `RagControllerTest`, `RagServiceTest`, `RealtimeAlertControllerTest`, `SeverityFeatureTest`, `BriefingServiceTest`(F7 계보 링크·조회) |
| FastAPI (9개) | `test_analyze`, `test_completion_criteria`, `test_documents`, `test_embedding_service`, `test_internal`, `test_rag_and_internal`, `test_service_boundaries`, `test_severity_rule_engine`, `test_vector_store_service` |
| React | `documentApi.test.js` (파일 검증 로직) |
| 스크립트 | `scripts/verify_erp_context.py` (ERP 정답셋 대조) |

### 자동 테스트가 없는 영역

이어받는 사람이 반드시 알아야 할 부분이다.

- **브리핑(12·13단계)** — `BriefingService`는 `BriefingServiceTest`로 F7 계보 링크·조립을 검증한다(Mock 기반). 단 `BriefingController`와 FastAPI `compose()`는 여전히 자동 테스트가 없고, 실 PostgreSQL 저장→조회 round-trip(`assessment_id` 포함)은 Swagger 수동 E2E로만 확인했다.
- **ERP 데이터 갱신 API 10종** — `ErpAdminService`에 자동 테스트가 없다.
- **브라우저 수동 E2E** — React 화면은 코드와 단위 테스트만 통과했고, 실제 브라우저 확인은 하지 않았다.

이 영역을 수정할 때는 회귀를 잡아줄 안전망이 없다는 뜻이므로, 손대기 전에 테스트를 먼저 추가하는 편이 안전하다.

## 9. 단계별 개발 과정

`n단계`는 마일스톤 `S0n`과 같은 번호다. 서로 독립된 기능 번호가 아니라, 앞 단계가 있어야 뒤 단계를 만들 수 있는 의존 순서다.

| 단계 | 관련 기능 | 구현 내용 | 상태 |
| --- | --- | --- | --- |
| 1. PostgreSQL 기본 환경 | 공통 기반 | Docker, DataSource, JPA, Flyway, Health | 완료 |
| 2. Master Data·문서 Schema | F6 + C1 | 자재·공급사·계약·문서 테이블과 Seed | 완료 |
| 3. C1-A 영구 저장 | C1 + C3 + C5/D1 | 원본 Volume, Metadata, 상태, Hash 중복 방지 | 완료 |
| 4. 추출·청킹 | C1 + F2 | 페이지, 조항·문단 우선 청킹 | 완료 |
| 5. ChromaDB | F2 + C5/C6 | Collection, 저장·조회·삭제·재적재 | 완료 |
| 6. Mock Embedding | F2 + M5 | 토큰 Hash Vector, Metadata Filter, 유사도 | 완료 |
| 7. C1-B 실제 E2E | C1 + F2 | Spring·PostgreSQL·FastAPI·Chroma 업로드·검색 | 완료 |
| 8. React 업로드 | C1 UI + C2 | 업로드, 상태, 중복, 오류, 새로고침 복원 | 코드·자동검증 완료 |
| 9. ERP 데이터 | F6 | V4 스키마, ERP 조회 구조, 갱신 API 10종 | 완료 |
| 10. ERP 영향 계산 | F1 | `ErpService` 결정적 수식과 Context API | 완료 |
| 11. Severity Rule Engine | F3/F8 | Spring 요청·저장, FastAPI 규칙 엔진 | 완료 |
| 12. RAG 대응 분석 | F2 + F9 | 근거 검색, 적격 대체 공급사, 근거 부족 표시 | 완료 |
| 13. 템플릿 브리핑 | F5 | 12단계와 통합. 생성·저장·조회 | 완료 |
| 14. 전체 조회·React | F11 | 목록·대시보드 조회 API와 역할별 화면 | 착수 |
| 15. 전체 Docker 통합 | 공통 기반 | 5개 서비스 통합, 장애 시나리오 | 완료 |
| 16. 실제 모델 교체 | M | Embedding·XGBoost·LLM 실제 연결 | 부분 착수 (Embedding 배선 완료, XGBoost·LLM 대기) |

8단계는 표현에 주의가 필요하다. 검증용 React의 코드·단위 테스트·production build는 통과했지만, 프론트엔드 팀의 정식 프로젝트와 병합하지 않았고 브라우저 수동 확인도 남아 있다.

## 10. 남은 작업

### 14단계 전체 조회·React 대시보드

백엔드는 완료됐고(Dashboard·Risk·Contract·Briefing 요약/목록 조회 API), 남은 것은 프론트엔드 쪽이다.

- ~~Dashboard·Risk·Contract·Briefing **목록** 조회 API~~ — 완료 (`DashboardController`, 브리핑 목록 포함). 단, 계획과 실제 경로가 다르다(아래 각주).
- 정식 React 프로젝트와 업로드 화면 병합, 역할별 대시보드
- 로그인 화면이 저장하는 Token Key와 `access_token` 통일
- 브라우저 수동 E2E — 정상 PDF/TXT/CSV 업로드, 중복·실패, 새로고침 복원
- ERP 데이터 갱신 UI 연동 (백엔드 API 10종은 완료)

**계획 대비 실제 (S14 각주)** — 계획서의 API 목록과 실제 코드가 두 군데 다르다. 실수가 아니라 "실데이터가 있는 것 + 화면이 실제 필요한 것"만 지은 결과다.

- 계획의 `GET /api/v1/risks`, `GET /api/v1/risks/{id}` → **`GET /api/v1/risk-events`로 신설**(2026-07-27 갱신). 프론트엔드 화면 절반 이상이 `RiskEvent` 계약(`market_context`·`erp_view`·`quality_check`·`rag_view`) 위에 지어져 있어, "백엔드가 프론트 계약에 맞춘다" 방침에 따라 그 모양 그대로 반환하는 조회 API(`RiskEventController`/`RiskEventService`)를 추가했다. **데이터는 F4 수집·F3(XGBoost) 모델 배선 전까지 결정론적 placeholder**이며, 모델·에이전트가 오면 `RiskEventService` 구현부만 교체한다(엔드포인트 계약 불변). 자재 축의 `GET /api/v1/dashboard/materials`(ERP+Severity 실데이터)는 리스크 목록을 대체하는 게 아니라 **자재별 집계 용도로 별도 유지**한다.
  - _이전 각주(폐기): "`/risks` 구현 안 함, dashboard/materials로 대체" — dashboard/materials(자재별 severity)는 RiskEvent(뉴스+erp_view+rag_view)와 구조가 달라 프론트 RiskEvent 수요를 못 메우므로 위와 같이 갱신._
- 계획의 `GET /api/v1/contracts/{id}`(단건) → **보류**. `GET /api/v1/contracts`(목록)만 구현했다. 계약 본문은 RAG 검색·브리핑 근거로 이미 도달 가능하고, 단건 상세를 소비할 화면이 아직 없어서다. 필요해지면 추가한다.

> 프론트엔드 팀 작업과 맞물리므로 착수 전 범위 협의가 필요하다.

### 15단계 전체 Docker 통합

완료. 5개 서비스(postgres·chroma·fastapi·spring·frontend)를 하나의 `docker-compose.yml`로 통합했고, 서비스 개별 장애 시나리오 4종을 검증했다. 상세는 [s15-failure-scenario-verification.md](s15-failure-scenario-verification.md) 참고.

- 잔여(의도적 보류): postgres 다운 시 30초 행(HikariCP `connection-timeout`) 하드닝 — 우선순위 낮아 후속 과제

### 16단계 실제 모델 교체

Embedding만 부분 착수, 나머지는 대기.

- ~~`EmbeddingProvider` 계약을 만족하는 OpenAI 구현체 작성·주입~~ — 완료 (`OpenAIEmbedding`, `dependencies.py` 배선). **남은 것**: `.env`에 실제 키 투입 후 기존 청크 재적재
- XGBoost 영향 도메인 분류기 연결 — 팀 모델 전달 대기
- `briefing_service.py` 섹션 함수를 LLM 호출로 교체 — Claude API(Anthropic) 예정, 키 확보 후 착수

> ERP 데이터는 교체 대상이 아니다. 1절 참고.

### 이월된 개별 이슈

- `as_of` 타임존 정규화 — `spring.jackson.time-zone` 설정 또는 커스텀 Deserializer (보류)
- `agent-csv` 정답셋 REQ-007의 `nextEtaDays` 빈칸이 실제 설계와 불일치 — 정답셋 수정 여부 팀 확인 필요
- 사건·뉴스(F4) 수집과 ERP Context 연결 — 현재 브리핑은 지정한 자재 기준으로만 생성된다
- 12·13단계와 ERP 갱신 API의 자동 테스트 추가 (8절 참고)

## 11. 조원에게 설명할 때 사용할 요약

> 문서 업로드부터 브리핑 생성까지 백엔드 파이프라인을 연결했습니다. Spring이 파일 검증·원본 저장·업무 데이터·인증을 맡고, FastAPI가 문서 추출·청킹·임베딩·벡터 검색·규칙 계산·템플릿 조립을 맡도록 책임을 나눴습니다. React는 FastAPI를 직접 부르지 않고, FastAPI는 PostgreSQL을 직접 보지 않습니다.
>
> 브리핑은 Spring이 ERP 수치·위험 등급·계약 근거·적격 대체 공급사를 모아 FastAPI에 넘기면, FastAPI가 규칙으로 문장을 골라 값만 채우는 방식입니다. LLM을 쓰지 않아 같은 입력에는 항상 같은 브리핑이 나오고, 계약 근거가 없으면 "근거 부족"이라고 정직하게 표시합니다. 나중에 실제 모델이 준비되면 이 템플릿 함수와 임베딩 구현체만 교체하면 됩니다.
>
> 1~13단계는 구현과 검증을 마쳤고, 남은 것은 14단계 전체 조회 API와 대시보드, 15단계 Docker 통합, 16단계 실제 모델 교체입니다. 다만 12·13단계는 수동 E2E로만 검증했고 자동 테스트가 없다는 점은 감안해야 합니다.
