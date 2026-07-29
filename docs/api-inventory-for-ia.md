# 백엔드 API 인벤토리 — 계층별 IA 확립용

작성 2026-07-29 · 목적: **계층별(비로그인/구매팀/경영기획팀/경영진) 정보구조 논의의 입력 자료**

프론트엔드 측이 지적한 대로 데모 이미지·요구사항 정의서·기획정의서 어디에도 **계층 구분**이
없습니다. 그 논의를 하려면 "지금 백엔드가 무엇을 내줄 수 있는가"가 먼저 보여야 해서 만든 목록입니다.

**엔드포인트 46개 중 프론트에 연결된 것은 8개**입니다.

---

## 1. 한눈에

| 구분 | 개수 | 비고 |
|---|---|---|
| 전체 엔드포인트 | **46** | |
| 프론트 연결됨 | **8** | 인증 3 + 공개 5 |
| 미연결 | **38** | 대부분 화면 배치가 안 정해져서 |
| 응답이 placeholder | **2** | `/risk-events`, `/map/realtime-alerts` |

---

## 2. 현재 연결된 8개

| 엔드포인트 | 화면 | 인증 |
|---|---|---|
| `POST /api/v1/auth/signup` | 회원가입 | 공개 |
| `POST /api/v1/auth/login` | 로그인 | 공개 |
| `GET /api/v1/auth/me` | 세션 확인 | 토큰 |
| `GET /api/v1/public/risk-board` | 공개 대시보드 — 글로벌 리스크 지도 | 공개 |
| `GET /api/v1/public/recommendations` | 공개 대시보드 — AI 권고 조치 ⚠️ | 공개 |
| `GET /api/v1/public/news-feed` | 공개 대시보드 — 실시간 뉴스 속보 | 공개 |
| `GET /api/v1/public/price-trends` | 공개 대시보드 — 원자재 가격 추이 | 공개 |
| `GET /api/v1/public/price-summaries` | 공개 대시보드 — 요약 카드 | 공개 |

> ⚠️ **`/public/recommendations`는 계층 재배치 후보입니다.** 프론트엔드 측 제안(조치 리스트는
> 구매팀 전용)에 동의합니다. 근거: 나머지 공개 3종은 *외부에서도 관측 가능한 사실*(어디서 무슨 일이
> 났는가, 시장가가 어떤가)인 반면, 권고는 *우리 회사의 대응 방침*입니다. 특히 현재 문구
> `"즉시 대체 조달처 검토 필요 — 대체 후보 3곳 확인됨"`은 공급사명을 가려도 **협상 포지션**을 드러냅니다.
>
> **기준 제안: 사실은 공개, 처방은 내부.**
>
> 이동 비용은 낮습니다 — `SecurityConfig`의 permitAll 경로에서 빼고 URL을 `/api/v1/recommendations`로
> 옮기면 됩니다. **서비스 로직은 변경 없음.**

---

## 3. 미연결 38개 — 계층 배치가 필요한 것들

### 3-1. 업무 조회 (계층 배치 논의 대상) ★

| 엔드포인트 | 내용 | 데이터 상태 |
|---|---|---|
| `GET /api/v1/risk-events` | 리스크 이벤트 목록 (erp_view·rag_view·quality_check 포함) | ⚠️ **placeholder** — 조인 키 미확정 |
| `GET /api/v1/dashboard/summary` | 대시보드 집계 | ✅ 실데이터 |
| `GET /api/v1/dashboard/materials` | 자재별 현황 | ✅ 실데이터 |
| `GET /api/v1/dashboard/import-dependency` | 수입 의존도 | ✅ 실데이터 |
| `GET /api/v1/contracts` | 계약 목록 | ✅ 실데이터 |
| `GET /api/v1/briefings` | 브리핑 목록 | ✅ 실데이터 |
| `GET /api/v1/briefings/{id}` | 브리핑 상세 | ✅ 실데이터 |
| `GET /api/v1/briefings/{id}/lineage` | 근거 계보 (F7) | ✅ 실데이터 |
| `GET /api/v1/analyses/{id}` | 분석 결과 조회 | ✅ 실데이터 |
| `GET /api/v1/severity/assessments/{id}` | 심각도 판정 조회 | ✅ 실데이터 |
| `GET /api/v1/map/realtime-alerts` | 실시간 알림 지도 | ⚠️ **placeholder** (mock 고정 응답) |
| `GET /api/v1/suppliers/qualified` | 자재별 적격 공급사 | ✅ 실데이터 (현재 permitAll) |

**이 12개가 IA 표를 채울 때 실제로 배분해야 할 항목**입니다.

`GET /api/v1/suppliers/qualified`는 현재 **비로그인 접근 가능**합니다(F9 콜백 용도로 permitAll).
공급사명이 그대로 나가므로 **재검토 대상**입니다.

### 3-2. 사용자 액션

| 엔드포인트 | 내용 | 누가 쓰나 |
|---|---|---|
| `POST /api/v1/analyses` | 분석 요청 | 구매팀? |
| `POST /api/v1/briefings` | 브리핑 생성 | 구매팀? |
| `POST /api/v1/severity/assessments` | 심각도 판정 요청 | 구매팀? |
| `POST /api/v1/rag/search` | 계약서 검색 | 구매팀? |
| `POST /api/v1/documents` | 문서 업로드 | 구매팀? |
| `GET /api/v1/documents/{id}` | 문서 조회 | |
| `POST /api/v1/documents/{id}/reprocess` | 문서 재처리 | |

### 3-3. 인증 부가

| 엔드포인트 | 내용 |
|---|---|
| `POST /api/v1/auth/refresh` | 토큰 갱신 (프론트 미구현) |
| `POST /api/v1/auth/logout` | 로그아웃 (프론트는 로컬 토큰 삭제만) |
| `POST /api/v1/auth/users/{id}/approve` | 가입 승인 — **관리자 화면 자체가 없음** |

### 3-4. 운영·내부 (화면 불필요)

| 그룹 | 개수 | 내용 |
|---|---|---|
| `POST /api/v1/collection/**` | 4 | 수집 수동 실행, 과거 아카이브 적재, 테스트 뉴스 |
| `POST /api/v1/erp/admin/**` | 10 | ERP 마스터 적재 (materials, suppliers, contracts …) |
| `POST /api/v1/erp/context`, `/erp/exposure` | 2 | FastAPI ↔ Spring 내부 연동 |

이 16개는 화면에 붙일 필요가 없습니다. 다만 **`/erp/admin/**`이 지금 일반 인증만 통과하면
호출 가능**하므로, 역할(Role) 기반 제한이 필요한지 검토가 필요합니다.

---

## 4. IA 표 (채워야 할 것)

| 정보 | 출처 API | 비로그인 | 구매팀 | 경영기획팀 | 경영진 |
|---|---|---|---|---|---|
| 글로벌 리스크 지도 | `/public/risk-board` | ✅ 현재 | ? | ? | ? |
| 실시간 뉴스 속보 | `/public/news-feed` | ✅ 현재 | ? | ? | ? |
| 원자재 가격 추이 | `/public/price-trends` | ✅ 현재 | ? | ? | ? |
| **AI 권고 조치** | `/public/recommendations` | ⚠️ **재검토** | ? | ? | ? |
| 리스크 이벤트 상세 (ERP·계약 근거) | `/risk-events` | ✗ | ? | ? | ? |
| 대시보드 집계 | `/dashboard/summary` | ? | ? | ? | ? |
| 자재별 현황 | `/dashboard/materials` | ? | ? | ? | ? |
| 수입 의존도 | `/dashboard/import-dependency` | ? | ? | ? | ? |
| 계약 목록 | `/contracts` | ✗ | ? | ? | ? |
| 브리핑 / 근거 계보 | `/briefings/**` | ✗ | ? | ? | ? |
| 적격 공급사 | `/suppliers/qualified` | ⚠️ **현재 공개** | ? | ? | ? |
| 문서 업로드 | `/documents` | ✗ | ? | ? | ? |

---

## 5. 이미 지켜지고 있는 원칙

공개 화면 설계에서 확립된 규칙이 있습니다. IA 논의의 출발점으로 쓸 수 있습니다.

> **비로그인에는 ERP 내부 상세를 노출하지 않는다** — `erp_view`(재고 소진일수), `quality_check`(인증 판정),
> `rag_view`(계약 조항)는 공개 subset에서 제외. (`RiskEventDto.RiskBoardItem` 주석)

> **리스크 판단 신뢰도 라벨(확정/참고/경고)은 전 화면 필수** (Seq 20, `requirements-frontend.md:39`)

> **등급(심각/주의/정상)은 판정이 있을 때만 표시한다** — 판정하지 않은 대상에 "정상"을 붙이지 않는다.
> (공개 지도의 UNKNOWN 제외, 뉴스 속보의 optional grade)

---

## 6. 답: "API 목록 연결은 아직 고려하지 마?"

**아니요, 지금 고려해야 합니다.** 다만 *구현*이 아니라 *배치 결정*을 말합니다.

- **지금 할 것**: 위 4번 표 채우기 → 어느 API가 어느 계층 화면에 붙는지 확정
- **지금 하지 말 것**: 화면 연결 구현 (표가 바뀌면 재작업)
- **IA 이후**: 분석 ↔ 브리핑 연결 (`docs/join-key-decision.md`) — IA 표에서 "리스크 이벤트 상세"
  행이 어떻게 채워지느냐에 따라 **필요 여부 자체가 갈립니다.** 상세 화면이 없으면 연결도 불필요합니다.

표가 채워지면 미연결 38개 중 실제로 붙일 것이 추려지고, 그때부터는 **패널당 30분~1시간**짜리
기계적인 작업입니다. 공개 화면 4개 패널이 그랬듯이요.
