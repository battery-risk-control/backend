# 대시보드·리스크 연동 가이드 (프론트 배선용)

> auth 연동은 완료된 상태를 전제로 한다. 이 문서는 그다음 화면들 — **리스크 이벤트·대시보드·계약·브리핑·지도** — 을 실제 백엔드에 붙이기 위한 가이드다.
> 모든 응답 예시는 2026-07-27 시드 켠 백엔드(`localhost:8080`)에서 **실호출로 캡처한 실제 값**이다.

---

## 0. 접속 정보

- **주소**: `http://localhost:8080` (프론트에서 `VITE_API_BASE_URL=http://localhost:8080`으로 직접 호출)
- **환경변수**: `VITE_API_BASE_URL` (auth와 동일하게 이미 사용 중)
- **CORS**: `5173`(dev)·`4173`(preview)·`3000` 허용됨 — 추가 설정 불필요
- **테스트 계정** (비번 전부 `test1234!`): `purchasing@test.local` / `planning@test.local` / `executive@test.local`, 승인대기 재현용 `pending@company.com`(비번 `anything`)

---

## 1. 공통: 토큰 부착 (제일 중요) 🔑

`auth`를 제외한 **모든 `/api/v1/**` 엔드포인트는 Bearer 토큰 필수**다. 토큰 없이 호출하면 401.

- 로그인 응답에서 토큰 위치: **`data.access_token`** (검증됨).
- 이미 만들어 둔 **`fetchWithAuth(path, token)`** 를 쓰면 됨 — `{success, data}` 봉투를 자동으로 벗겨 `data`만 돌려준다.
- **토큰 저장(협의 ⑥ 미결)**: 최소 메모리 유지도 동작하지만, **새로고침 후에도 대시보드가 뜨게 하려면 `localStorage`가 필요**하다(메모리면 새로고침 시 토큰 소실 → 전부 401). 이 화면들부터는 사실상 localStorage 전환을 권장.

```ts
// 로그인 성공 후 토큰을 저장해 두고
const { access_token } = await login(...)      // data.access_token
saveToken(access_token)                        // 메모리 or localStorage

// 이후 인증 API는 전부 fetchWithAuth
const events = await fetchWithAuth<RiskEvent[]>('/api/v1/risk-events', getToken())
```

> ⚠️ 예외: `/api/v1/map/realtime-alerts` **한 개만** 봉투가 없어서 `fetchWithAuth`/`fetchJson`을 쓰면 안 된다 → **4절** 참고.

---

## 2. mock 함수 ↔ 백엔드 매핑 한눈에

| 프론트 mock 함수 | 백엔드 | 봉투 | 어댑터 | 현재 데이터 |
| --- | --- | --- | --- | --- |
| `fetchRiskEvents()` | `GET /risk-events` | ✅ 자동 | 불필요(구조 동일) | placeholder 4건 |
| `fetchRiskEventBriefing(id)` | `GET /briefings/{id}` | ✅ 자동 | ⚠️ 키 불일치(UUID) | 0건 |
| `fetchGlobalRiskBoard()` | `GET /map/realtime-alerts` | ❌ **없음** | ⚠️ 좌표·등급 | mock 1건 |
| `fetchMaterialRiskGauges()`·`fetchScoreCards()` | `GET /dashboard/materials` | ✅ 자동 | ⚠️ 필드·등급 매핑 | **빈 배열** |
| `fetchImportDependency()` | `GET /dashboard/import-dependency` | ✅ 자동 | ⚠️ 필드 매핑 + 파라미터 | — |
| (계약 목록 화면) | `GET /contracts` | ✅ 자동 | 페이지네이션 | 실데이터 29건 |
| `fetchPlanningDashboard()`·`fetchExecutiveDashboard()`·`fetchNewsFeed()`·`fetchAiRecommendations()` | — | — | **파생 유지** (아래 참고) | — |
| `fetchMaterialPriceTrends()`·`fetchMaterialPriceSummaries()` | 없음 | — | **mock 유지** | — |

**핵심 지렛대**: 프론트의 `fetchPlanningDashboard`·`fetchExecutiveDashboard`·`fetchGlobalRiskBoard`·`fetchNewsFeed`·`fetchAiRecommendations`는 전부 `fetchRiskEvents()`에서 **파생**된다. 따라서 **`fetchRiskEvents`만 실 HTTP로 바꾸면 2·3계층·공개 화면·뉴스가 자동으로 실 데이터 경로를 탄다.** 먼저 hub 하나부터 붙일 것.

---

## 3. 바로 붙는 것 (`fetchWithAuth` + 봉투 자동)

### 3.1 리스크 이벤트 hub — `GET /api/v1/risk-events`

응답 `data`가 프론트 `RiskEvent[]` 구조 **그대로**라 어댑터가 필요 없다.

```ts
// 구현부만 교체, 반환 타입 유지
export async function fetchRiskEvents(): Promise<RiskEvent[]> {
  return fetchWithAuth<RiskEvent[]>('/api/v1/risk-events', getToken())
}
```

실응답(검증):
```json
{"success":true,"data":[
  {"risk_event_id":"RISK-2026-0721-001","grade":"심각","confidence_label":"확정",
   "market_context":{"source":"data_ingestion_layer","material":"니켈","event_summary":"인도네시아 니켈 수출 관세 인상…","country_code":"ID","country_name":"인도네시아","coordinates":{"lat":-6.2088,"lng":106.8456}},
   "erp_view":{...},"quality_check":{...},"rag_view":{...},"output_artifacts":{"render_mode":"json","file_url":null,"fallback_to_json":true}}
  … 총 4건 …
]}
```

> ⚠️ 지금 4건은 **모델 배선 전까지의 결정론적 placeholder**다. 뉴스·XGBoost 파이프라인이 붙으면 내용만 실데이터로 바뀌고 **계약(구조)·URL은 그대로**다. 즉 프론트는 지금 붙여두면 나중에 다시 안 고쳐도 된다.

### 3.2 계약 목록 — `GET /api/v1/contracts?status=&page=0&size=20`

페이지네이션 구조. `data.content`가 배열이다.

```json
{"success":true,"data":{"content":[
  {"contract_id":2,"erp_contract_id":"CTR-001","contract_number":"BA-2025-0001",
   "contract_name":"Lithium Carbonate Supply Agreement 1","erp_supplier_id":"SUP-CHL-01",
   "supplier_name":"Atacama Lithium Partners","erp_material_id":"MAT-LI-CARB",
   "material_name":"Lithium Carbonate","status":"ACTIVE","contract_role":"PRIMARY",
   "start_date":"2026-01-01","end_date":"2026-12-31"}
 ],"page":0,"size":20,"total_elements":29,"total_pages":2}}
```
실데이터 29건 존재. `fetchWithAuth`가 `data`(= `{content, page, …}`)를 돌려주므로 목록은 `result.content`.

### 3.3 대시보드 요약 — `GET /api/v1/dashboard/summary`

```json
{"success":true,"data":{
  "assessed_material_count":0,"critical_count":0,"warning_count":0,"normal_count":0,"unknown_count":0,"briefing_count":0,
  "material_count":11,"supplier_count":19,"contract_count":29,"document_count":4,
  "latest_assessed_at":null,"mock":true}}
```
- `material_count`·`supplier_count`·`contract_count`·`document_count`는 **실제 값**.
- ⚠️ `*_count`(critical/warning/…) 등급 집계는 **현재 0** — severity 분석이 아직 안 돌아서다(6절).

### 3.4 자재별 리스크 — `GET /api/v1/dashboard/materials?severity=&limit=20`

`data` = `MaterialRiskItem[]`. `fetchMaterialRiskGauges()`·`fetchScoreCards()`가 여기서 파생된다. 단 **필드·등급 매핑이 필요**하다.

응답 필드(`MaterialRiskItem`):
```
erp_material_id, material_name, severity(NORMAL|WARNING|CRITICAL|UNKNOWN),
score, inventory_days, safety_stock_days, supplier_dependency_ratio,
feoc_status, data_quality_status, assessed_at
```
매핑 예: `MaterialRiskGaugeItem.name ← material_name`, `.grade ← severity`(아래 등급 변환), `basis`·`changeLabel`은 백엔드에 없으니 프론트에서 채우거나 생략.

> ⚠️ **현재 빈 배열 `[]`** — severity_assessments가 없어서다(6절). 화면은 "데이터 없음" 상태를 견디게 해둘 것.

**등급 변환 (severity → RiskGrade)** — 대시보드·지도 공통:

| 백엔드 | 프론트 |
| --- | --- |
| `CRITICAL` | `심각` |
| `WARNING` | `주의` |
| `NORMAL` | `정상` |
| `UNKNOWN` | (정책 결정 — 미표시 or 별도 뱃지) |

### 3.5 수입 의존도 — `GET /api/v1/dashboard/import-dependency?erp_material_id=MAT-LI-CARB`

⚠️ **`erp_material_id` 쿼리 파라미터 필수**(없으면 400). 유효 id는 `/contracts`·`/dashboard/materials`의 `erp_material_id`(예: `MAT-LI-CARB`)에서 얻는다.

응답 `data`(`ImportDependency`):
```
erp_material_id, material_name, total_share_ratio,
breakdown: [{ erp_supplier_id, supplier_name, country_code, supply_share_ratio, approved_status, is_alternative }]
```
프론트 `ImportDependencyData` 매핑: `total ← total_share_ratio`, `breakdown[].label ← supplier_name`(또는 country_code), `.value ← supply_share_ratio`, `.color`는 프론트가 지정, `year`는 백엔드에 없으니 생략/고정.

### 3.6 브리핑 상세 — `GET /api/v1/briefings` / `GET /api/v1/briefings/{briefingId}`

목록은 페이지네이션(`{content, page, size, total_elements, total_pages}`), 현재 **0건**.

> ⚠️ **키 불일치**: 백엔드 단건 키는 **UUID `briefingId`**인데 프론트 `fetchRiskEventBriefing(riskEventId)`는 `RISK-YYYY-MMDD-NNN`로 조회한다. 지금은 매칭이 불가능(리스크 이벤트↔브리핑 조인 미확정 — 모델 era 과제). **1단계에선 브리핑 상세는 mock 유지**를 권장하고, 목록 화면만 `/briefings`에 붙인다.

---

## 4. 특별 케이스: 지도 — `GET /api/v1/map/realtime-alerts`

⚠️ **이 엔드포인트만 `{success, data}` 봉투가 없다.** (조장 승인 관제 공통 스키마라 백엔드는 안 바꾼다.) 따라서 `fetchWithAuth`/`fetchJson`을 쓰면 `data`를 벗기려다 깨진다 → **봉투를 안 벗기는 raw 호출**로 받아야 한다.

실응답(검증, top-level에 바로 `alerts`):
```json
{"timestamp":"2026-07-21T11:45:00Z","alerts":[
  {"coordinates":[113.9213,-0.7893],"event_id":123456789,"country_code":"ID","country_name_kr":"인도네시아",
   "affected_materials":["Nickel"],
   "news_info":{"title":"…","url":"…","impact_domain":"생산","summary_kr":"…"},
   "risk_assessment":{"final_level":"High","ai_evidence":{…}}}
]}
```

배선 시 어댑터 2가지:
- **좌표**: `coordinates`는 `[경도, 위도]` 순 → 프론트 `{lat, lng}`로 뒤집기 (`{lat: c[1], lng: c[0]}`).
- **등급**: `risk_assessment.final_level`은 `High`/`Medium`/`Low` → `심각`/`주의`/`정상`.

```ts
// 이 엔드포인트만 봉투 없이 받는 전용 헬퍼
const res = await fetch(`${API_BASE_URL}/api/v1/map/realtime-alerts`, { headers: { Authorization: `Bearer ${getToken()}` } })
const { alerts } = await res.json()   // ← data 안 벗김
```

---

## 5. mock 유지 (백엔드 없음)

- `fetchMaterialPriceTrends()` / `fetchMaterialPriceSummaries()` — 가격 데이터 소스 없음. **mock 유지.**
- 브리핑 상세(`fetchRiskEventBriefing`) — 3.6의 키 불일치로 **당분간 mock 유지.**

---

## 6. 지금 데이터 상태 — "비어 보임"은 정상 ⚠️

이 백엔드는 **뉴스·모델(F3/F4) 파이프라인 이전 단계**라, 리스크 "숫자"는 대부분 비어 있다. 화면 뼈대·연결을 검증하는 데는 지장 없지만, 혼동 방지를 위해:

| 엔드포인트 | 현재 |
| --- | --- |
| `/risk-events` | placeholder 4건 (화면 채우기용) |
| `/dashboard/summary` | 마스터 count 실제 / 등급 집계 0 |
| `/dashboard/materials` | **빈 배열** (severity 없음) |
| `/briefings` | 0건 |
| `/contracts` | 실데이터 29건 ✅ |

> 대시보드 게이지·스코어가 채워진 걸 보고 싶으면 백엔드에서 `POST /api/v1/severity/assessments`로 몇 건 만들 수 있다(백엔드 담당에게 요청). 연결 검증만이면 `/risk-events`(placeholder)와 `/contracts`(실데이터)로 충분.

---

## 7. 검증 완료 (2026-07-27)

시드 켠 백엔드에서 실호출로 확인한 항목:
- ✅ 테스트 계정 로그인 → 토큰 발급 (`data.access_token`)
- ✅ `GET /risk-events` → `RiskEvent[]` 4건, snake_case·한글 그대로
- ✅ 토큰으로 `/dashboard/summary`·`/contracts`·`/auth/me` 200
- ✅ `org_tier` 변환 정상 (`role:PURCHASING ↔ org_tier:purchasing`; `STRATEGY↔planning`도 동일 경로)
- ✅ `/map/realtime-alerts` 봉투 없음 재확인

## 8. 권장 배선 순서

1. **토큰 저장/부착** 확정(⑥) — 이게 되면 나머지는 기계적.
2. **`fetchRiskEvents` → `/risk-events`** (hub, 어댑터 0). 2·3계층·공개 화면이 자동으로 실 HTTP로 전환됨.
3. **계약 목록 → `/contracts`** (실데이터라 바로 보람 있음).
4. **`/dashboard/*`** (등급 변환 + 빈 상태 처리).
5. **지도 → `/map/realtime-alerts`** (봉투 없음 전용 헬퍼 + 좌표/등급 어댑터).
6. 가격·브리핑 상세는 mock 유지.
