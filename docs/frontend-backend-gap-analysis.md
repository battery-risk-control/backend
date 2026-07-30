# 프론트엔드 ↔ 백엔드 연동 격차 분석

- 작성일: 2026-07-24
- 프론트엔드 레포: `C:\aivleschool\bigproject\frontend\frontend` (Phase 9.4까지 진행)
- 백엔드 레포: `battery-risk-mvp-starter` (S13까지 구현·검증 완료)
- 목적: 두 레포를 실제로 연결하기 전에 **무엇이 맞고 무엇이 다른지** 확정하고, 팀 논의가 필요한 결정 사항을 명시한다.

---

## 1. 현재 상태 요약

| | 상태 |
| --- | --- |
| 프론트엔드 화면 | 3계층 대시보드 + 공개 대시보드 + 인증 화면 완성 (Playwright e2e 24개 통과, CI 구축) |
| 프론트엔드 ↔ 백엔드 연동 | **0%** — `src/` 전체에 `fetch`/`axios` 호출이 하나도 없음. 전부 `api/*.api.ts` mock |
| 계약 정의 위치 | `src/api/types.ts` + `docs/mock-schemas.md` (프론트엔드가 "잠정 계약"으로 선언) |
| 백엔드 API | 23개 (인증 5, 문서 3, RAG 1, ERP 조회 1, ERP 갱신 10, Severity 2, 지도 1) + 브리핑 2 |

프론트엔드 README 명시:
> "백엔드 API 계약은 아직 확정되지 않았습니다. … 실제 계약이 확정되면 각 `api/*.api.ts` 파일의 구현부(mock 데이터를 실제 fetch 호출로 교체)만 바꾸면 됩니다."

즉 **프론트엔드는 교체 지점을 이미 격리해뒀고, 백엔드가 계약을 맞춰주면 되는 구조**다.

---

## 2. 근본 격차 — 데이터 모델이 다르다

프론트엔드의 중심 개념은 **`risk_event`(뉴스 기반 리스크 이벤트)**이고, 백엔드의 중심 개념은 **자재·공급사·재고(ERP)**다.

```text
프론트엔드 관점                        백엔드 관점
risk_event (뉴스 사건)                 materials / suppliers (마스터)
  ├─ market_context (뉴스·국가·좌표)      ├─ inventory_snapshots (재고)
  ├─ erp_view (재고·대체공급사)           ├─ severity_assessments (위험판정)
  ├─ rag_view (계약 조항)                └─ briefings (브리핑)
  └─ quality_check (신뢰도)
```

**핵심**: 백엔드에 `risk_events` 테이블이 **없다**. 이건 F3(AI 분석)·F4(외부 데이터 수집) 영역이며 현재 미구현이다. 프론트엔드 화면의 절반 이상이 이 데이터를 요구한다.

---

## 3. 프론트엔드가 기대하는 API 12개 vs 백엔드 현황

| # | 프론트엔드 함수 | 필요 데이터 | 백엔드 대응 | 판정 |
| --- | --- | --- | --- | --- |
| 1 | `login()` | email, password → access_token, org_tier | `POST /api/v1/auth/login` | ⚠️ 필드명 불일치 |
| 2 | `signup()` | name, email, password, org_tier → PENDING | `POST /api/v1/auth/signup` | ⚠️ 필드명 + 승인상태 없음 |
| 3 | `fetchRiskEvents()` | risk_event 전체 | 없음 | ❌ |
| 4 | `fetchRiskEventBriefing(id)` | rag_view + output_artifacts | `GET /api/v1/briefings/{id}` | ⚠️ 구조 다름 (변환 필요) |
| 5 | `fetchGlobalRiskBoard()` | 국가·좌표·등급 | 없음 | ❌ |
| 6 | `fetchAiRecommendations()` | 권고 조치 | 브리핑의 `recommended_checks` 유사 | ⚠️ 부분 |
| 7 | `fetchNewsFeed()` | 뉴스 헤드라인·출처 | 없음 | ❌ |
| 8 | `fetchMaterialPriceTrends()` | 가격 시계열 | 없음 | ❌ |
| 9 | `fetchMaterialPriceSummaries()` | 가격 변동률·리스크점수 | 없음 | ❌ |
| 10 | `fetchMaterialRiskGauges()` | 자재별 등급 게이지 | Severity로 파생 가능 | ⚠️ 부분 |
| 11 | `fetchScoreCards()` | 점수 카드 | Severity `score` 활용 가능 | ⚠️ 부분 |
| 12 | `fetchImportDependency()` | 수입 의존도 도넛 | `supply_share_ratio`로 파생 가능 | ⚠️ 부분 |
| 13 | `fetchPlanningDashboard()` | 사업부별 노출도, 협력사 이력 | 없음 (`business_unit` 필드 부재) | ❌ |
| 14 | `fetchExecutiveDashboard()` | 누적 KPI, 절감 시뮬레이션 | 없음 | ❌ |

**집계: 즉시 가능 0 / 매핑하면 가능 7 / 데이터 자체가 없음 7**

---

## 4. 즉시 해결해야 할 필드명 불일치

### 4-1. `org_tier` 값 체계 — **가장 시급**

| 계층 | 프론트엔드 | 백엔드 |
| --- | --- | --- |
| 1계층 구매팀 | `purchasing` | `PURCHASING` |
| 2계층 경영기획팀 | **`planning`** | **`STRATEGY`** |
| 3계층 경영진 | `executive` | `EXECUTIVE` |

대소문자 차이는 사소하지만 **`planning` vs `STRATEGY`는 단어 자체가 다르다.** 백엔드는 DB CHECK 제약(`ck_users_role`)과 `Role` enum에 박혀 있어 변경 시 마이그레이션이 필요하다.

> **결정 필요**: 백엔드를 `PLANNING`으로 바꿀지, 프론트엔드를 `strategy`로 바꿀지, 아니면 API 계층에서 매핑할지.

### 4-2. 로그인 식별자

| | 프론트엔드 | 백엔드 |
| --- | --- | --- |
| 로그인 ID | `email` | `username` |
| 회원가입 필드 | name, email, password, org_tier | username, password, name, role |

> **결정 필요**: 백엔드 `users` 테이블에 `email` 컬럼을 추가할지, `username`에 이메일을 넣을지.

### 4-3. 승인 대기(PENDING) 개념

프론트엔드에는 **관리자 승인 대기 화면**이 있고, 회원가입 응답이 `status: 'PENDING'`, 로그인 시 `error: 'PENDING_APPROVAL'`을 기대한다.

백엔드 `users` 테이블에는 `enabled BOOLEAN`만 있고 **PENDING/APPROVED 상태 개념이 없다.** 회원가입하면 바로 로그인된다.

> **결정 필요**: 승인 플로우를 백엔드에 구현할지(컬럼 추가 + 승인 API), 아니면 프론트엔드에서 해당 화면을 제외할지.

### 4-4. 리스크 등급 체계

| | 프론트엔드 `RiskGrade` | 백엔드 `severity` |
| --- | --- | --- |
| 값 | 3단계 (정상/주의/심각 추정) | `NORMAL`/`WARNING`/`CRITICAL`/`UNKNOWN` |

백엔드에는 `UNKNOWN`이 추가로 있다(데이터 부족 시). 프론트엔드가 이걸 어떻게 표시할지 정의되어 있지 않다.

### 4-5. 신뢰도 라벨 (`confidence_label`)

프론트엔드의 **핵심 원칙**("하이브리드 신뢰도 표시 — 모든 리스크 판단에 확정/참고/경고 라벨")인데, **백엔드에 대응 필드가 없다.**

가장 가까운 것은 `data_quality_status`(`VALID`/`STALE`/`INCOMPLETE`/`INVALID`)와 `mock` 플래그다.

> **결정 필요**: `data_quality_status` + `mock`을 조합해 `confidence_label`을 파생할지, 백엔드에 전용 필드를 추가할지.

---

## 5. 데이터가 아예 없어 만들 수 없는 것 (F3/F4 영역)

아래는 **뉴스 수집·AI 분석(F3/F4, 김수린 담당)이 완성돼야** 가능하다. 백엔드 담당(김민지) 범위 밖이다.

| 화면 | 필요한 것 | 관련 기능 |
| --- | --- | --- |
| 글로벌 리스크 관제 맵 | 국가코드·좌표·이벤트 요약 | F4 수집 + F6 국가 표준화 |
| 실시간 뉴스 속보 | 뉴스 헤드라인·출처·발행일 | F4 수집 |
| AI 기반 권고 조치 | 이벤트별 권고 문장 | F3 분석 |
| 원자재 가격 추이 | 가격 시계열 | F4 수집 (yfinance 등) |
| 사업부별 리스크 노출도 | `business_unit` 매핑 | 신규 설계 필요 |
| 협력사 리스크 이력(90일) | 이벤트-공급사 연결 이력 | F3 + 이력 테이블 |
| 누적 KPI (탐지/대응 건수) | 이벤트 탐지·대응 이력 | F3/F12(삭제됨) |

> 프론트엔드 `docs/mock-schemas.md`에도 "risk_event에는 가격 필드가 없어 합성 지수 사용", "자재→사업부 매핑은 데모용 고정 가정"이라고 명시돼 있다 — 프론트엔드도 이 데이터가 없다는 걸 알고 mock으로 채운 상태.

---

## 6. 권장 연동 순서

### 1단계 — 인증 연결 (격차 최소, 성과 가시적)
- 필드명 매핑 결정 (`email`/`org_tier`/PENDING)
- 프론트엔드 `api/auth.api.ts`의 mock을 실제 `fetch`로 교체
- 성과: "실제 로그인 → 계층별 대시보드 진입"이 동작

### 2단계 — 이미 있는 데이터 노출
백엔드에 데이터가 있어 **지금 만들 수 있는** 조회 API:
- `GET /api/v1/briefings` (목록, 페이지네이션) — 현재 단건 조회만 있음
- `GET /api/v1/dashboard/summary` — `severity_assessments` 집계 (심각/주의 건수)
- 자재별 리스크 게이지 — 자재별 최신 Severity 조회
- 수입 의존도 — `supplier_materials.supply_share_ratio` 집계

### 3단계 — `risk_event` 도입 여부 결정 (팀 논의)
- **(a)** F3/F4 완성까지 해당 화면은 mock 유지 → 백엔드 작업 없음
- **(b)** 백엔드에 `risk_events` 테이블 + Mock 데이터를 만들어 화면부터 연결 → S14 범위 확장

---

## 7. 팀 논의가 필요한 결정 5가지

1. **`org_tier`**: `planning` ↔ `STRATEGY` 중 무엇으로 통일할 것인가
2. **로그인 식별자**: `email` 추가 vs `username` 유지
3. **승인 대기(PENDING)**: 백엔드에 구현할 것인가
4. **`confidence_label`**: 파생할 것인가, 신규 필드로 만들 것인가
5. **`risk_event`**: F3/F4를 기다릴 것인가, Mock 테이블을 먼저 만들 것인가

> 1~4는 백엔드에서 비교적 작은 수정으로 해결 가능하다. **5번이 전체 일정에 가장 큰 영향**을 준다.
