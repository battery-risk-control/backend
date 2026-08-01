# 백엔드 API 인벤토리 — 계층별 IA 확립용

작성 2026-07-29 · 개정 2026-07-29(전수 재검증) · 목적: **계층별(비로그인/구매팀/경영기획팀/경영진) 정보구조 논의 자료**

> **검증 방법**: 추측 없이 `controller/*.java`와 `app/api/**/*.py`를 파싱해 클래스 prefix + 메서드 매핑을
> 전부 추출했습니다. 아래 숫자는 코드 기준 실측입니다.
>
> **Spring 46개** (비즈니스 API, Actuator 제외) · **FastAPI 14개** (내부 전용)
>
> FastAPI는 2026-07-30에 `/internal/ml/classify`(규칙 기반 mock, Spring 미호출)를 제거해 15개 → 14개가 됐습니다.

---

## 0. ⚠️ 먼저 — 기존 노션 API 문서에 12개가 빠져 있습니다

기존 문서 기준 34개인데 실제는 46개입니다. **아래 12개가 누락돼 있으니 참고 시 유의해 주세요.**

| 누락된 엔드포인트 | 왜 중요한가 |
|---|---|
| `GET /api/v1/public/news-feed` | **공개 대시보드에 이미 연결됨** (실시간 뉴스 속보) |
| `GET /api/v1/public/price-trends` | **공개 대시보드에 이미 연결됨** (원자재 가격 추이) |
| `GET /api/v1/public/price-summaries` | **공개 대시보드에 이미 연결됨** (요약 카드) |
| `GET /api/v1/suppliers/qualified` | 적격 공급사 — **현재 비로그인 접근 가능** |
| `GET /api/v1/briefings/{id}/lineage` | 근거 계보(F7) — 브리핑의 판단 근거 추적 |
| `POST /api/v1/analyses` · `GET /api/v1/analyses/{id}` | 분석 요청·결과 조회 |
| `POST /api/v1/erp/exposure` | ERP 노출도 계산(soojung ERP Agent) |
| `POST /api/v1/collection/**` (4개) | 수집 운영용 — 화면 불필요 |

기존 문서의 FastAPI 목록도 5개가 빠져 있습니다(`/internal/market/prices`,
`/internal/realtime-pipeline/fetch-and-triage`, `/internal/multi-agent/briefings`,
`/internal/erp/exposure`, `/suppliers/recommend`). 다만 FastAPI는 **Spring만 호출하는 내부 API**라
프론트 IA와는 무관합니다(§5 부록).

---

## 1. 한눈에

| 구분 | 개수 |
|---|---|
| Spring 비즈니스 API | **49** |
| └ 프론트 연결됨 | **11** (인증 3 + 공개 5 + 리스크 모니터링 3) |
| └ 미연결 | **38** |
| └ 응답이 placeholder | **2** (`/risk-events`, `/map/realtime-alerts`) |
| FastAPI (내부 전용, IA 무관) | 14 |
| Actuator (프레임워크 제공) | 2 |

**미연결 38개의 성격**

| 분류 | 개수 | IA 논의 대상? |
|---|---|---|
| 업무 조회 | **12** | ⭐ **여기가 핵심** |
| 사용자 액션 (생성·업로드) | 7 | ⭐ 대상 |
| 인증 부가 | 3 | 일부 |
| 운영·내부 (화면 불필요) | 16 | ✗ |

---

## 2. 🎯 계층별 배치 — **제안 초안**

빈칸으로 두면 채우기 어려울 것 같아 **먼저 제안을 채워봤습니다.** 확정이 아니라 반박용 초안이니
편하게 덮어써 주세요. 근거는 §3에 적었습니다.

범례: ✅ 전체 제공 · ⚠️ 제한 제공(요약·읽기 전용) · ✗ 미제공

| # | 정보 / 기능 | 출처 API | 비로그인 | 구매팀 | 경영기획팀 | 경영진 |
|---|---|---|---|---|---|---|
| 1 | 글로벌 리스크 지도 | `/public/risk-board` | ✅ | ✅ | ✅ | ✅ |
| 2 | 실시간 뉴스 속보 | `/public/news-feed` | ✅ | ✅ | ✅ | ⚠️ 요약 |
| 3 | 원자재 가격 추이 | `/public/price-trends`<br>`/public/price-summaries` | ✅ | ✅ | ✅ | ✅ |
| 4 | **AI 권고 조치** | `/public/recommendations` | **✗** | ✅ | ⚠️ 열람 | ✗ |
| 5 | 리스크 이벤트 상세<br>(ERP·계약 근거) | `/risk-events` | ✗ | ✅ | ⚠️ ERP만 | ✗ |
| 6 | 대시보드 집계 | `/dashboard/summary` | ✗ | ✅ | ✅ | ✅ |
| 7 | 자재별 현황 | `/dashboard/materials` | ✗ | ✅ | ✅ | ⚠️ 상위 N |
| 8 | 수입 의존도 | `/dashboard/import-dependency` | ✗ | ✅ | ✅ | ✅ |
| 9 | 계약 목록 | `/contracts` | ✗ | ✅ | ⚠️ 열람 | ✗ |
| 10 | 브리핑 · 근거 계보 | `/briefings/**` | ✗ | ✅ | ⚠️ 열람 | ✗ |
| 11 | **적격 공급사** | `/suppliers/qualified` | **✗** (현재 ✅) | ✅ | ✗ | ✗ |
| 12 | 심각도 판정 조회 | `/severity/assessments/{id}` | ✗ | ✅ | ⚠️ 열람 | ✗ |
| 13 | 실시간 알림 지도 | `/map/realtime-alerts` | ? | ? | ? | ? |
| — | **[액션]** 문서 업로드·재처리 | `/documents/**` | ✗ | ✅ | ✗ | ✗ |
| — | **[액션]** 분석 요청 | `POST /analyses` | ✗ | ✅ | ✗ | ✗ |
| — | **[액션]** 브리핑 생성 | `POST /briefings` | ✗ | ✅ | ✗ | ✗ |
| — | **[액션]** 계약서 검색(RAG) | `POST /rag/search` | ✗ | ✅ | ⚠️ | ✗ |
| — | **[액션]** 가입 승인 | `/auth/users/{id}/approve` | ✗ | ✗ | ✗ | ✗ **(관리자 화면 부재)** |

**13번은 제안을 못 냈습니다** — `/map/realtime-alerts`는 응답이 고정 Mock이고 `/public/risk-board`와
용도가 겹칩니다. 둘 중 하나로 통합하거나 폐기 판단이 필요합니다.

---

## 3. 제안의 근거 — 기준 4가지

**① 사실은 공개, 처방은 내부**
1~3번(지도·뉴스·가격)은 외부에서도 관측 가능한 사실입니다. 4번 권고 조치는 우리 회사의 대응
방침이라 성격이 다릅니다. 현재 문구 `"대체 후보 3곳 확인됨"`은 공급사명을 가려도 **협상 포지션**을
드러냅니다. → 4번 비로그인 ✗

**② 실행 액션은 구매팀 전용**
생성·업로드·요청은 실무 담당자의 일입니다. 경영기획팀·경영진은 결과를 보는 쪽입니다.

**③ 위로 갈수록 집계, 아래로 갈수록 상세**
경영진에게 개별 뉴스 상세나 계약 조항은 과잉입니다. 반대로 수입 의존도(8번)는 전략 지표라
경영진에 ✅입니다.

**④ 공급사 식별 정보는 구매팀만**
11번 적격 공급사는 공급사명이 그대로 나갑니다. 지금 비로그인 접근이 가능한 건 **F9에서 FastAPI가
Spring으로 콜백하기 때문**(`SecurityConfig`의 `permitAll`)이지 공개 의도가 아닙니다.
→ 내부 호출은 통과시키되 외부는 차단하는 방식이 필요합니다.

---

## 3-2. 판단이 애매할 때 — 시나리오 대신 이 기준을 쓰면 됩니다

"누가 볼 수 있는가"로 따지면 경우의 수가 끝없이 늘어납니다(사내망이면 괜찮은가, 견학 온 외부인은,
협력사 방문자는…). **질문을 바꾸면 유한해집니다.**

> ### "누가 보나" ❌ → **"이 정보가 새면 무엇이 곤란한가"** ✅

곤란함의 종류는 우리 도메인에서 네 가지뿐입니다.

| 등급 | 성격 | 새면 생기는 일 | 해당 정보 | 비로그인 |
|---|---|---|---|---|
| **D** | 외부 관측 가능한 사실 | 없음 — 뉴스·시장가는 원래 공개 | 리스크 지도, 뉴스 속보, 가격 추이 | ✅ |
| **C** | 내부 판단·방침 | 우리 대응 계획이 드러남 | AI 권고 조치, 심각도 판정 | ✗ |
| **B** | 거래 관계 | 공급사 간 형평 문제, 협상력 손실 | 적격 공급사명, 계약 목록·조항 | ✗ |
| **A** | 내부 취약점 | **협상 상대에게 카드가 됨** — "이 회사 리튬 재고 6일" | 재고 소진일수, 공급사 의존도, 수입 의존도 | ✗ |

같은 사건이라도 등급이 갈립니다. *"칠레 리튬 광산 파업"*(D, 공개) 과 *"그래서 우리 재고가 6일 남았다"*(A, 비공개)는 다릅니다.

### 기본값: **애매하면 제한**

판단이 반반이면 막아두는 쪽으로 둡니다. **나중에 푸는 건 10분이지만, 이미 보여준 걸 거두는 건 불가능**하기 때문입니다.

### 그 앞에 답하면 더 쉬워지는 질문 하나

> **비로그인 화면은 왜 존재하는가?**

- **(a) 로그인 전 랜딩** — 로그인 유도가 목적 → 정보는 최소, D등급도 일부만
- **(b) 사내 공용 현황판** (로비 모니터 등) — 누구나 보는 게 목적 → D등급 전부 ✅
- **(c) 데모·발표용 쇼케이스** — 기능을 보여주는 게 목적 → D등급 전부 ✅ + 나머지는 로그인 유도

이 하나만 정해지면 §2 표의 비로그인 열이 사실상 자동으로 채워집니다. 시나리오를 나열할 필요가 없습니다.

---

## 4. 전체 API 목록 (코드 실측)

### 4-1. 프론트 연결됨 — 11개

| Method | Endpoint | 화면 | 인증 |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | 회원가입 | 공개 |
| POST | `/api/v1/auth/login` | 로그인 | 공개 |
| GET | `/api/v1/auth/me` | 세션 확인 | 토큰 |
| GET | `/api/v1/public/risk-board` | 공개 — 글로벌 리스크 지도 | 공개 |
| GET | `/api/v1/public/recommendations` | 공개 — AI 권고 조치 ⚠️재배치 후보 | 공개 |
| GET | `/api/v1/public/news-feed` | 공개 — 실시간 뉴스 속보 | 공개 |
| GET | `/api/v1/public/price-trends` | 공개 — 원자재 가격 추이 | 공개 |
| GET | `/api/v1/public/price-summaries` | 공개 — 요약 카드 | 공개 |
| GET | `/api/v1/risk-monitoring/events` | 1계층 구매팀 — 리스크 모니터링 목록 | 토큰 |
| GET | `/api/v1/risk-monitoring/events/{eventId}` | 1계층 구매팀 — 이벤트 상세 | 토큰 |
| POST | `/api/v1/risk-monitoring/events/{eventId}/erp-impact` | 1계층 구매팀 — ERP·계약 영향 분석 실행 | 토큰 |
| GET | `/api/v1/contract-rag/contracts` | 1계층 구매팀 — 계약 목록(적재된 계약만) | 토큰 |
| POST | `/api/v1/contract-rag/search` | 1계층 구매팀 — 계약 조항 의미검색 | 토큰 |
| GET | `/api/v1/contract-rag/contracts/{contractId}` | 1계층 구매팀 — 계약 문서 상세·임베딩 상태 | 토큰 |
| POST | `/api/v1/contract-rag/contracts/{contractId}/documents` | 1계층 구매팀 — 계약서 추가 업로드 | 토큰 |
| POST | `/api/v1/contract-rag/contracts/{contractId}/reprocess` | 1계층 구매팀 — 문서 재처리 | 토큰 |
| POST | `/api/v1/contract-rag/briefings` | 1계층 구매팀 — 계약 근거 기반 AI 브리핑 | 토큰 |
| GET | `/api/v1/ai-briefing/context` | 1계층 구매팀 — AI 브리핑 대상 프리필 | 토큰 |
| POST | `/api/v1/ai-briefing/briefings` | 1계층 구매팀 — LLM 브리핑 생성·저장 | 토큰 |
| GET | `/api/v1/ai-briefing/briefings` | 1계층 구매팀 — 최근 브리핑 목록 | 토큰 |
| GET | `/api/v1/ai-briefing/briefings/{briefingId}` | 1계층 구매팀 — 브리핑 상세 보기 | 토큰 |

**계약·RAG 6종(2026-08-01 신설)** — 기존 `/api/v1/rag/search`(멀티에이전트용, 필터 필수)와
**경로를 분리했다.** 화면은 검색창에 문장만 넣고 전체 계약을 훑는 흐름이라 필터를 강제할 수
없어서, FastAPI에 `/api/v1/contract-rag/search`를 따로 두고 필터 없이도 컬렉션 전체를 조회한다
(기존 검색 규칙·멀티에이전트 경로는 그대로다).

조항 제목(`clause_title`)은 ChromaDB에 저장된 값이 아니라 청크 본문 머리에서 뽑아 만든다 —
ERP 연결 시드가 영문 계약서라 `Article 4 / DELIVERY AND PENALTY`가 들어오고, 화면에는
`제4조 · 납기 및 지연 위약금`으로 보인다(`ContractRagService.CLAUSE_LABELS_KO`).

`/briefings`는 뉴스를 새로 수집하지 않는다. 계약의 자재 대분류로 **이미 저장된 최신 분석**을
찾아 멀티에이전트를 실행하며, 관련 뉴스가 없으면 422다(계약 상세의 `briefing_available`로
미리 알 수 있다). 응답의 `composite=false`는 KG 게이트 조기 종료라 점수가 무의미하다는 뜻이다.

**리스크 모니터링 3종(2026-08-01 신설)** — 원천이 **수집 뉴스(`raw_events`)** 라서 식별자가
`RISK-YYYY-...`가 아니라 `raw_events.id`(숫자)다. 분석(F3)이 안 붙은 기사도 목록에 나와야 하기
때문이다(`analysis-enabled=false`가 기본값). 등급은 멀티에이전트가 끝났으면 종합 위험도,
아니면 외부신호 기준 **잠정값**이며 `multi_agent_completed`로 구분한다 —
자세한 판정 규칙은 `RiskMonitoringService` javadoc 참고.

**AI 브리핑 4종(2026-08-01 신설)** — 앞의 세 화면이 각자 돌리던 멀티에이전트를 **한 화면으로 모은
것**이다. 화면들의 버튼은 이제 실행 대신 `?source=NEWS|MATERIAL|CONTRACT&ref=...`로 이동만 하고,
`/context`가 그 대상의 "분석 대상 · ERP 연결"을 채운다(그래프를 돌리지 않는다). 실행은
`POST /briefings`뿐이며 `use_llm`은 **생략 시 true**다 — 버튼 이름이 "LLM 브리핑 생성"이라
누르면 문구 생성까지 도는 것이 화면의 약속이라, 기본 false인 다른 화면들과 반대다.

기존 세 엔드포인트(`/risk-monitoring/.../erp-impact`, `/material-risk/.../briefing`,
`/contract-rag/briefings`)는 **그대로 남아 있다.** 계약을 깨지 않으려는 것이고, 실행 가능 판정도
그쪽 응답의 `erp_impact_available`·`briefing_available`을 AI 브리핑이 그대로 빌려 쓴다.

브리핑 본문·권고조치·ERP 노출 근거·계약 근거는 새 테이블 `ai_briefings`(V23)에 저장된다.
점수 이력은 기존대로 `procurement_risk_assessments`에 남고 `assessment_id`로 연결된다 —
그쪽은 스케줄러·타 화면 실행까지 섞이는 이력이라 "최근 브리핑" 목록의 원천으로 쓸 수 없다.

### 4-2. 업무 조회 — 12개 (IA 핵심 대상)

| Method | Endpoint | 내용 | 데이터 |
|---|---|---|---|
| GET | `/api/v1/risk-events` | 리스크 이벤트 목록(erp_view·rag_view·quality_check 포함) | ⚠️ **placeholder** |
| GET | `/api/v1/dashboard/summary` | 대시보드 집계 | ✅ 실데이터 |
| GET | `/api/v1/dashboard/materials` | 자재별 현황 | ✅ 실데이터 |
| GET | `/api/v1/dashboard/import-dependency` | 수입 의존도 | ✅ 실데이터 |
| GET | `/api/v1/contracts` | 계약 목록 | ✅ 실데이터 |
| GET | `/api/v1/briefings` | 브리핑 목록 | ✅ 실데이터 |
| GET | `/api/v1/briefings/{briefingId}` | 브리핑 단건 | ✅ 실데이터 |
| GET | `/api/v1/briefings/{briefingId}/lineage` | 근거 계보(F7) | ✅ 실데이터 |
| GET | `/api/v1/analyses/{analysis_id}` | 분석 결과 조회 | ✅ 실데이터 |
| GET | `/api/v1/severity/assessments/{assessmentId}` | 심각도 판정 조회 | ✅ 실데이터 |
| GET | `/api/v1/map/realtime-alerts` | 실시간 알림 지도 | ⚠️ **고정 Mock** |
| GET | `/api/v1/suppliers/qualified` | 자재별 적격 공급사 | ✅ 실데이터 · ⚠️현재 공개 |

### 4-3. 사용자 액션 — 7개

| Method | Endpoint | 내용 |
|---|---|---|
| POST | `/api/v1/analyses` | 분석 요청 |
| POST | `/api/v1/briefings` | 브리핑 생성 |
| POST | `/api/v1/severity/assessments` | 심각도 판정 실행·저장 |
| POST | `/api/v1/rag/search` | 계약서 근거 검색(벡터) |
| POST | `/api/v1/documents` | 파일 업로드(multipart) |
| GET | `/api/v1/documents/{document_id}` | 처리 상태 조회 |
| POST | `/api/v1/documents/{document_id}/reprocess` | 재적재(재임베딩) |

> 프론트에서 **문서·업로드 컴포넌트를 추가 개발** 예정이라면 이 그룹의 3개(`/documents/**`)가
> 바로 필요합니다. 백엔드는 실동작 상태입니다.

### 4-4. 인증 부가 — 3개

| Method | Endpoint | 비고 |
|---|---|---|
| POST | `/api/v1/auth/refresh` | access_token 재발급 — **프론트 미구현** |
| POST | `/api/v1/auth/logout` | 토큰 블랙리스트 — 프론트는 로컬 삭제만 |
| POST | `/api/v1/auth/users/{userId}/approve` | 가입 승인 — **관리자 화면 자체가 없음** |

### 4-5. 운영·내부 — 16개 (화면 불필요)

| Method | Endpoint 그룹 | 개수 | 내용 |
|---|---|---|---|
| POST | `/api/v1/collection/run`, `/run/{source}`, `/import-historical`, `/test-news` | 4 | 수집 수동 실행·아카이브 적재·테스트 |
| POST | `/api/v1/erp/admin/**` | 10 | ERP 마스터 Upsert (materials·suppliers·warehouses·contracts·supplier-materials·inventory-snapshots·material-consumptions·purchase-orders·purchase-order-items·goods-receipts) |
| POST | `/api/v1/erp/context`, `/api/v1/erp/exposure` | 2 | FastAPI ↔ Spring 내부 연동 |

> ⚠️ `/erp/admin/**` 10개는 **로그인만 하면 누구나 호출 가능**합니다. ERP 마스터를 수정하는
> 엔드포인트라 역할(Role) 제한이 필요한지 검토가 필요합니다.

### 4-6. Actuator — 2개 (프레임워크 제공)

`GET /actuator/health` · `GET /actuator/info` — Docker healthcheck·모니터링용.

---

## 5. 부록: FastAPI 14개 (내부 전용 — IA 무관)

React는 Spring만 호출하고, FastAPI는 Spring만 호출합니다(PostgreSQL 직접 접근 없음).
**프론트 IA 논의 대상이 아닙니다.** 경계가 코드로 지켜지고 있음을 확인하는 용도입니다.

| Method | Endpoint | 내용 |
|---|---|---|
| POST | `/api/v1/analyze` | 통합 분석 파이프라인(F3 오케스트레이션) |
| POST | `/api/v1/documents/process` | 문서 추출·청킹·임베딩·Chroma 적재 |
| POST | `/api/v1/rag/contracts` | 계약서 청크 적재 |
| POST | `/api/v1/rag/search` | 벡터 검색 |
| POST | `/api/v1/internal/llm/extract` | 뉴스 정보 추출(LLM) |
| POST | `/api/v1/internal/severity/score` | Severity 규칙 계산(v1, ERP 노출도) |
| POST | `/api/v1/internal/briefings` | 브리핑 생성(구버전 진입점) |
| POST | `/api/v1/internal/briefings/compose` | 템플릿 브리핑 조립(S13) |
| POST | `/api/v1/internal/multi-agent/briefings` | 멀티에이전트 브리핑 |
| POST | `/api/v1/internal/erp/exposure` | ERP 노출도 계산(ERP Agent) |
| POST | `/api/v1/internal/realtime-pipeline/fetch-and-triage` | GDELT 수집·XGBoost 트리아지(F4) |
| POST | `/api/v1/internal/market/prices` | 원자재 가격 수집(yfinance) |
| POST | `/api/v1/suppliers/recommend` | 대체 공급사 추천(F9) |
| GET | `/health` | FastAPI·Chroma Health |

---

## 6. 이미 확립된 원칙 (새로 정할 필요 없음)

공개 화면 작업에서 이미 지켜지고 있는 규칙입니다. IA 논의의 출발점으로 쓸 수 있습니다.

> **비로그인에는 ERP 내부 상세를 노출하지 않는다** — `erp_view`(재고 소진일수),
> `quality_check`(인증 판정), `rag_view`(계약 조항)는 공개 subset에서 제외.
> (`RiskEventDto.RiskBoardItem` 주석)

> **리스크 판단 신뢰도 라벨(확정/참고/경고)은 전 화면 필수** (Seq 20, `requirements-frontend.md:39`)

> **등급(심각/주의/정상)은 판정이 있을 때만 표시한다** — 판정하지 않은 대상에 "정상"을 붙이지 않는다.
> (공개 지도의 UNKNOWN 제외, 뉴스 속보의 optional grade)

---

## 7. 결정하면 이어지는 것

- **§2 표가 채워지면** → 미연결 38개 중 붙일 것이 확정되고, 패널당 30분~1시간짜리 기계적 작업이 됩니다
- **5번 행(리스크 이벤트 상세)이 "✗"로 채워지면** → `docs/join-key-decision.md`의 분석↔브리핑 연결이
  **불필요해집니다.** 반대로 "✅"면 그 문서의 1순위 질문을 결정해야 합니다
- **13번 행** → `/map/realtime-alerts`를 `/public/risk-board`로 통합할지 판단 필요

### MVP 범위 관련 질문 하나

로그인 계층 3개를 정말 나눌지, MVP에서는 **구매팀 하나로 갈지**가 정해지면 위 표의 칸이
크게 줄어듭니다. 원래 데모 이미지가 구매팀 기준만 있었다는 점도 같이 고려할 만합니다.
