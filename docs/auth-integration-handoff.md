# 인증 연동 협의 사항 (백엔드 → 프론트엔드)

- 작성일: 2026-07-24
- 백엔드 담당: 김민지 / 프론트엔드 담당: 김영진
- 목적: 프론트엔드 `src/api/auth.api.ts`의 mock을 실제 백엔드 호출로 교체하기 위해 **먼저 합의해야 할 사항**을 정리한다.

## 0. 백엔드 준비 상태 (완료)

프론트엔드 `api/types.ts` 계약에 맞춰 백엔드를 이미 수정하고 E2E 검증까지 마쳤다. **프론트엔드 타입 변경은 필요 없다.**

| 프론트엔드 기대 | 백엔드 제공 | 상태 |
| --- | --- | --- |
| `login({email, password})` | `POST /api/v1/auth/login` — `email`로 로그인 가능 | 검증 완료 |
| `{access_token, org_tier, status:'APPROVED'}` | 응답 최상위에 `access_token`/`org_tier`/`status` 포함 | 검증 완료 |
| `{error:'PENDING_APPROVAL', message}` | 승인 전 로그인 시 **HTTP 403** + `error.code='PENDING_APPROVAL'` | 검증 완료 |
| `signup({name,email,password,org_tier,org_name})` | `POST /api/v1/auth/signup` — 그대로 수용 | 검증 완료 |
| `{user_id, status:'PENDING', message}` | 응답에 `user_id`/`status` 포함 | 검증 완료 |
| `org_tier: 'purchasing'/'planning'/'executive'` | DB는 `PURCHASING/STRATEGY/EXECUTIVE`, **API에서 자동 변환** | 검증 완료 |

> 2계층이 백엔드 `STRATEGY` ↔ 프론트엔드 `planning`으로 단어가 다른데, **백엔드가 변환해서 내려주므로 프론트엔드는 신경 쓰지 않아도 된다.**

---

## 1. 협의가 필요한 사항

### (1) 백엔드 주소를 어떻게 설정할 것인가 — **가장 먼저 정해야 함**

현재 프론트엔드에는 `.env` 파일이 없고 `vite.config.ts`에도 프록시 설정이 없다. 실제 호출을 하려면 둘 중 하나가 필요하다.

| 방식 | 내용 | 장단점 |
| --- | --- | --- |
| A. 환경변수 | `.env`에 `VITE_API_BASE_URL=http://localhost:8080` 두고 `fetch(\`${import.meta.env.VITE_API_BASE_URL}/api/v1/auth/login\`)` | 배포 환경별 분리 쉬움. CORS 설정 필요 |
| B. Vite 프록시 | `vite.config.ts`에 `server.proxy['/api'] → localhost:8080` | 개발 중 CORS 회피. 배포 시 별도 설정 필요 |

> **백엔드는 이미 CORS를 허용해 뒀다** — `application.yml`의 `app.cors.allowed-origins`에 `http://localhost:5173`(Vite 기본 포트)이 포함되어 있어 A 방식도 바로 동작한다.

### (2) 응답 구조 차이 — 래퍼(`success`/`data`)

백엔드 모든 응답은 공통 래퍼로 감싸여 있다.

```json
{ "success": true, "data": { ...실제 내용... }, "timestamp": "..." }
```

프론트엔드 `LoginResponse` 타입은 래퍼 없이 `{access_token, org_tier, status}`를 기대한다.

> **협의**: `api/auth.api.ts`에서 `const body = await res.json(); return body.data` 로 벗겨낼지, 아니면 공용 `fetchJson()` 헬퍼를 만들어 전 API가 공유할지. 후자를 권장한다(어차피 다른 API도 전부 같은 래퍼다).

### (3) PENDING 응답을 어떻게 감지할 것인가

프론트엔드는 반환값에 `'error' in result`로 판별하는데, 백엔드는 **HTTP 403**으로 내려준다.

```json
// HTTP 403
{ "success": false, "error": { "code": "PENDING_APPROVAL", "message": "관리자 승인 대기 중입니다." } }
```

> **협의**: `res.ok`가 false일 때 `body.error.code === 'PENDING_APPROVAL'`이면 기존 `LoginPendingErrorResponse` 형태로 변환해 반환하는 방식을 권장한다. 그러면 `AuthPage`의 기존 분기 코드를 **그대로 유지**할 수 있다.

### (4) 테스트 계정 3종을 어떻게 처리할 것인가

현재 `auth.api.ts`에 하드코딩된 데모 계정(`purchasing@test.local` 등)은 mock 전용이라 실제 DB에는 없다.

> **협의**: 백엔드 DB에 동일한 계정 3개를 시드로 넣어줄지(개발 편의), 아니면 프론트엔드가 실제 회원가입 플로우로 계정을 만들지. **백엔드에서 시드로 넣어주는 것을 권장** — 프론트엔드 e2e 테스트(Playwright 24개)가 이 계정에 의존하고 있어 그대로 두면 테스트를 고치지 않아도 된다.

### (5) 승인(PENDING → APPROVED)은 누가 하는가

백엔드에 승인 API를 만들어 뒀다: `POST /api/v1/auth/users/{userId}/approve`

다만 현재는 **로그인한 사람이면 누구나 승인할 수 있다**(역할 제한 없음).

> **협의**: 관리자 화면을 만들 것인지, 아니면 당분간 Swagger로 수동 승인할 것인지. 관리자 역할이 필요하면 백엔드에 역할 제한을 추가해야 한다.

### (6) 토큰을 어디에 저장할 것인가

현재 프론트엔드 인증 상태는 **메모리 전용**(`AuthProvider`)이라 새로고침하면 사라진다. 백엔드 `access_token`은 1시간, `refresh_token`은 14일 유효하다.

> **협의**: 메모리 유지(새로고침 시 재로그인) vs `localStorage` 저장(새로고침 유지). 백엔드는 `POST /api/v1/auth/refresh`로 토큰 갱신을 지원하므로, 저장하기로 하면 갱신 로직도 함께 설계해야 한다.

### (7) 이후 API 호출에 토큰을 어떻게 붙일 것인가

인증이 필요한 모든 백엔드 API는 `Authorization: Bearer {access_token}` 헤더를 요구한다.

> **협의**: 공용 `fetchWithAuth()` 헬퍼를 만들어 두는 것을 권장한다. 앞으로 브리핑·ERP·대시보드 API를 붙일 때 매번 반복하지 않아도 된다.

---

## 2. 백엔드 API 요약 (연동에 필요한 것만)

```
POST /api/v1/auth/signup
  요청: { name, email, password, org_tier, org_name }
  응답 201: { success, data: { user_id, status: "PENDING", org_tier, ... } }

POST /api/v1/auth/login
  요청: { email, password }
  응답 200: { success, data: { access_token, refresh_token, org_tier, status, expires_in, user, ... } }
  응답 403: { success: false, error: { code: "PENDING_APPROVAL", message } }
  응답 401: { success: false, error: { code: "INVALID_CREDENTIALS", message } }

GET  /api/v1/auth/me            (Bearer 필요) → { role, org_tier, status, email, name, ... }
POST /api/v1/auth/logout        (Bearer 필요) → 토큰 무효화
POST /api/v1/auth/refresh       { refresh_token } → 새 access_token
POST /api/v1/auth/users/{id}/approve  (Bearer 필요) → PENDING 계정 승인
```

전체 명세는 Swagger에서 확인 가능하다: `http://localhost:8080/swagger-ui.html`

---

## 3. 권장 진행 순서

1. **(1) 백엔드 주소 방식**과 **(2) 래퍼 처리 방식**만 먼저 정한다 → 나머지는 코드 작성하며 정해도 된다
2. 프론트엔드에 공용 `fetchJson()`/`fetchWithAuth()` 헬퍼 추가
3. `auth.api.ts`의 `login`/`signup`을 실제 호출로 교체 (함수 시그니처·반환 타입은 그대로 유지)
4. 백엔드에서 테스트 계정 3종 시드 추가 (합의 시)
5. Playwright e2e 재실행으로 회귀 확인

> 프론트엔드 `api/types.ts`는 **수정할 필요가 없다** — 백엔드가 그 타입에 맞춰져 있다.
