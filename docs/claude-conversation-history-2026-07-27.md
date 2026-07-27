# Claude 대화 기록 (2026-07-25 ~ 2026-07-27)

battery-risk-mvp-starter 백엔드 작업 세션 기록. S16 재적재 스크립트 작성부터 F/C/M/D 기능 현황 정리, API 목록 검증·문서화까지 다뤘다.

> 이 문서는 대화 요약이다. 실제 코드 변경은 [implementation-summary-functions-and-steps.md](implementation-summary-functions-and-steps.md)와 [reindex_embeddings.py](../scripts/reindex_embeddings.py)에 반영돼 있다.

---

## 1. S16 Embedding 재적재 헬퍼 스크립트 작성

**요청**: OpenAI 키를 아직 못 넣었으니, 키 확보 후 기존 문서를 openai_v1 컬렉션으로 재적재할 헬퍼 스크립트를 미리 만들어 달라.

**배경 이해**:
- `EMBEDDING_PROVIDER`를 openai로 바꾸면 Chroma 컬렉션이 `contract_documents_mock_v1` → `contract_documents_openai_v1`로 갈린다.
- provider만 바꾸면 기존 문서가 openai_v1에 없어 RAG 검색이 0건이 된다 → 재적재 필요.
- 재적재는 기존 `POST /api/v1/documents/{id}/reprocess`를 문서마다 호출하면 된다(서버가 원본 파일을 다시 읽어 현재 provider로 재임베딩). mock_v1은 건드리지 않아 롤백 안전.

**확인한 코드 사실**:
- 인증: `/api/v1/**`는 전부 인증 필요, 로그인은 `POST /api/v1/auth/login` (`SecurityConfig.java`).
- 문서 목록 API·`findAll`이 없어서, 문서 ID는 PostgreSQL `contract_documents`에서 조회해야 한다.
- 응답 키는 snake_case (`chunk_count`, `embedding_type`, `mock` 등, `DocumentDto`).

**결과물**: [`scripts/reindex_embeddings.py`](../scripts/reindex_embeddings.py)
- stdlib만 사용(urllib·subprocess·argparse), 기존 `verify_erp_context.py` 스타일 계승.
- 봇 계정 로그인 → 문서 ID 조회(인자 또는 PostgreSQL) → 문서별 reprocess → 결과 표 출력.
- `--dry-run`으로 미리보기, `mock=true`로 적재되면 provider가 아직 mock이라고 경고.
- 문법 컴파일·dry-run 동작 검증 완료. 실제 재적재 경로는 키+스택 기동 후에만 검증 가능.

---

## 2. F/C/M/D 기능별 구현 현황 정리

**요청**: F/C/D/M 기능 중 구현/미구현 사항을 정리.

**방법**: 계획 문서가 아니라 실제 컨트롤러 9개(Spring)·서비스 12개(FastAPI)를 대조.

**4상태 구분**: ✅ 구현됨(Mock/규칙 포함) / 🟡 부분 구현 / ❌ 미구현 / ⬜ 범위 제외(의도적).

### 후속 질의로 확정한 판정

- **F1 → 🟡**: Spring 결정적 수치 계산은 실제, FastAPI 자연어 "해석"은 템플릿(LLM 미연동). `briefing_service.py`가 고정 문장 틀에 값만 채운다.
- **F11 → 🟡**: 대시보드 조회 API는 실데이터(ERP+Severity), `map/realtime-alerts`만 고정 Mock. 지도가 Mock인 이유는 뉴스 이벤트(F4 수집)·AI 근거(F3 모델)라는 상류 재료가 없어서다.
- **F12 삭제 무관**: Task 관리 영역이라 핵심 파이프라인과 무관.
- **C4(감사 로그) 삭제 OK**: 컴플라이언스 요건 없음, 기본 앱 로그로 충분.
- **C6이 "최소"인 이유**: Health는 있으나 Trace ID·단계별 처리시간·마지막 성공시각은 없음.
- **C7(LLM 검증)**: 지금은 실제 LLM이 없어 N/A에 가깝고, Claude 브리핑 LLM 연동 시 필수가 됨(모델 품질 검증과는 다른 층위 = 런타임 출력 검증).
- **M5 최소 구현 OK**: confidence·reason_codes·calculation_details 반환. 실시간 SHAP은 모델링 팀 몫.
- **M3(Registry) 미구현 이유**: 로드할 실제 XGBoost 모델 파일이 없어서(팀 학습 중). 모델 오면 소형 로더.
- **D1과 C5**: 개념은 다르나 문서에 대해선 `content_hash` Unique 하나로 동시 충족 = 같은 메커니즘.
- **D3(품질 검증)**: 타입·범위·Enum·FK 검증이라 **LLM 무관, 비용 0**, 이미 구현됨.
- **D4(단위·통화) 삭제 OK**: ERP_MOCK 단일 소스라 단위 일관, 스펙 고정만.

**최종 집계**: ✅ 13 · 🟡 5 · ❌ 3 · ⬜ 8.

→ [implementation-summary-functions-and-steps.md 2.1절](implementation-summary-functions-and-steps.md)에 표로 반영.

---

## 3. API 전체 목록 작성 및 3절 최신화

**요청**: 지금까지 생성한 API 목록 작성 + docs 3절 최신화.

**컨트롤러에서 직접 추출**한 결과, 기준 커밋(e728fbd, S14 이전)이라 3절에 빠진 항목 발견:
- 3.1(Spring): 브리핑 목록, 대시보드 3종(summary·materials·import-dependency), 계약 목록, actuator 추가.
- 3.2(FastAPI): `GET /health` 추가, 실제 호출 관계 문구 수정.

**용어**: 로그아웃은 "토큰 무효화"보다 **"토큰 블랙리스트 등록"**이 정확(`TokenBlacklistService`, `JwtAuthenticationFilter`가 요청마다 확인).

---

## 4. S14 계획 ↔ 실제 코드 차이

계획서 API 목록과 실제 코드가 두 군데 다르며, 실수가 아니라 "실데이터가 있는 것 + 화면이 실제 필요한 것"만 지은 결과:

- `GET /api/v1/risks`, `/risks/{id}` → **구현 안 함**. 나열할 실제 이벤트 피드(F4·F3)가 없어 `dashboard/materials`(ERP+Severity 실데이터)로 대체.
- `GET /api/v1/contracts/{id}`(단건) → **보류**. 목록만 구현. 계약 본문은 RAG·브리핑으로 도달, 단건 소비 화면 없음.

→ [14단계 섹션](implementation-summary-functions-and-steps.md)에 "S14 각주"로 반영.

---

## 5. 컨트롤러별 집계 검증 (23 → 31 정정)

사용자가 정리한 집계를 검증한 결과 오류 3가지 발견:
1. 인증(AuthController)이 6개인데 5개로 셈(`users/{userId}/approve` 누락).
2. 대시보드(4)·브리핑(3)이 집계에서 통째로 빠짐.
3. 그 결과 총 23 → 실제 **31**.

### 컨트롤러 9개 × 개수 (외부 API 31개)

| 컨트롤러 | class 경로 | 개수 |
| --- | --- | --- |
| AuthController | `/api/v1/auth` | 6 |
| DocumentController | `/api/v1/documents` | 3 |
| DashboardController | `/api/v1` (dashboard·contracts) | 4 |
| BriefingController | `/api/v1/briefings` | 3 |
| RagController | `/api/v1/rag` | 1 |
| ErpController | `/api/v1/erp` | 1 |
| SeverityController | `/api/v1/severity/assessments` | 2 |
| RealtimeAlertController | `/api/v1/map` | 1 (고정 Mock) |
| ErpAdminController | `/api/v1/erp/admin` | 10 |
| **합계** | **9개 컨트롤러** | **31** |

- Spring 합계: 외부 31 + Actuator 2 = **33**
- FastAPI 내부 API: **10**

→ [3.3절 컨트롤러별 집계](implementation-summary-functions-and-steps.md)로 반영.

---

## 6. Actuator·관측성 정리

**`/actuator/health`·`/actuator/info`**: 우리가 짠 `@RestController`가 아니라 **Spring Boot Actuator 자동 제공**.
- `health`: 앱+DB 등 의존성 생존 확인(모니터링·healthcheck용). `show-details: when_authorized`.
- `info`: 앱 메타정보(현재 거의 비어 있음).
- 설정은 `application.yml`, 인증 예외는 `SecurityConfig.java`. **컨트롤러 파일을 새로 만들면 안 됨**(존재하지 않는 코드).

**클래스 명명**: "어느 컨트롤러에 있나"를 쓸 땐 `RealtimeAlertController`·`DashboardController`처럼 `~Controller` 전체 이름이 맞다. `RealtimeAlert`·`Dashboard`는 도메인 이름(Controller/Service/DTO로 쪼개짐).

**관측성(Observability)**: "시스템이 정상인지, 문제 시 어디가 문제인지 밖에서 들여다볼 수 있는 능력"(자동차 계기판 비유). 우리 수준은 Health Check 3종(Spring·FastAPI·Chroma) = 생존 모니터링까지. "시스템 정상인지 모니터링"으로 표기해도 정직한 표현.

→ actuator 행을 "컨트롤러 없음 — Actuator 자동 제공, 설정 application.yml"로 반영.

---

## 이 세션에서 변경한 파일

- [scripts/reindex_embeddings.py](../scripts/reindex_embeddings.py) — 신규 (S16 재적재 헬퍼)
- [docs/implementation-summary-functions-and-steps.md](implementation-summary-functions-and-steps.md) — 2.1절(F/C/M/D 현황), 3.1·3.2·3.3절(API 목록·집계), 14단계 S14 각주

> 커밋은 사용자가 직접 수행. 실제 모델/키 대기 항목(XGBoost·Claude API·OpenAI 키 투입)은 [memory battery-risk-s16-model-plan] 참고.
