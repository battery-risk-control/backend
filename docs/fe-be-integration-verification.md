# FE↔BE 연동 검증 로드맵

> 작성 2026-07-27. 프론트↔백엔드(Spring) 연동을 **어디까지 검증할 수 있는지**와 그 방법을 정리한다.
> 방침: "백엔드가 프론트 계약에 맞춘다."

## ⚠️ 먼저 — 지금 화면으로 검증되는 건 "auth"뿐

프론트 코드 현재 상태:

- **auth(로그인/회원가입)** → 이미 실 백엔드 연결됨 → **화면으로 검증 가능** ✅
- **대시보드/리스크/계약/공개지도** → 프론트가 **아직 mock**이다(영진님이 연결 중). `VITE_API_BASE_URL`을 켜도 이 화면들은 여전히 mock 데이터를 보여준다. → **화면 검증은 영진님이 각 화면을 실 API로 바꾼 뒤** 가능. 그전엔 **API 레벨(curl/Swagger)로** 백엔드가 맞게 주는지만 검증한다.

즉 로드맵은 **① 지금(auth 화면 + 새 API는 curl)**, **② 영진님 연결 후(화면 전체)** 두 층이다.

### 여기까지밖에 안 되는 이유

두 층이 독립적으로 화면 검증을 막고 있다:

1. **프론트 mock 미해제**: 대시보드/리스크/계약/공개지도 화면은 영진님이 mock 데이터를 실 API 호출로 아직 안 바꿨다. 화면 코드가 mock을 참조하므로 백엔드를 잘 띄워도 반영 안 됨. auth만 실 연동 코드가 붙어 유일하게 화면 검증 가능.
2. **백엔드 분석 파이프라인 미가동**: `/risk-events`, `/dashboard/*`는 엔드포인트 계약은 완성됐지만 뒤에서 채워줄 분석(F3 XGBoost, F4 뉴스수집)이 아직 안 붙어 placeholder/0건. 영진님이 연결을 끝내도 지금은 "실 API를 호출하는데 데이터는 placeholder"가 정상 상태다.

---

## 1. FE 연동용 API 목록

| 화면/기능 | 엔드포인트 | 인증 | 현재 데이터 |
| --- | --- | --- | --- |
| 로그인/회원가입/내정보 | `POST /auth/login`·`/auth/signup`, `GET /auth/me` | me만 Bearer | 실계정(시드) |
| 리스크 목록(구매팀) | `GET /risk-events` | Bearer | placeholder 4 |
| 대시보드 | `GET /dashboard/summary`·`/materials`·`/import-dependency` | Bearer | 마스터 실데이터, severity는 0 |
| 계약 목록 | `GET /contracts` | Bearer | 실데이터 29 |
| 브리핑 | `GET /briefings`·`/briefings/{id}` | Bearer | 0(분석 전) |
| **공개 지도** | `GET /public/risk-board` | **무인증** | placeholder 4 |

## 2. 백엔드 서버 켜기

```bash
cd C:\aivleschool\bigproject\battery-risk-mvp-starter
AUTH_TEST_SEED_ENABLED=true docker compose up -d --build spring
docker compose stop frontend    # 임시 프론트(5173) 내려서 진짜 프론트에 포트 양보
```

- `up -d spring`은 spring + 의존(postgres·fastapi·chroma)만 켠다(임시 프론트 안 켜짐).
- `--build`는 코드 바뀌었을 때만(이미 떠 있으면 생략 가능).
- **헬스 확인**: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}` 나오면 OK.

## 3. 프론트엔드 서버 켜기 (⭐ 진짜 프론트)

```bash
cd C:\aivleschool\bigproject\frontend
npm install          # 처음 한 번만
```

`.env.local` 파일을 만들어 백엔드 주소를 지정(이게 있어야 auth가 실 백엔드로 감):

```
VITE_API_BASE_URL=http://localhost:8080
```

그다음:

```bash
npm run dev
```

→ 브라우저에서 뜨는 주소(`http://localhost:5173`) 접속.

## 4. 검증 시나리오 A — auth (지금 화면으로 검증 가능) ✅

| # | 화면에서 하는 동작 | 기대 결과 | 증명되는 것 |
| --- | --- | --- | --- |
| A1 | `/` 접속 | 공개 대시보드가 로그인 없이 뜸 | 공개 라우트 |
| A2 | `/auth`에서 `purchasing@test.local` / `test1234!` 로그인 | **`/purchasing` "구매팀 대시보드"로 이동** | 실 FE→실 BE 로그인 + 토큰 + org_tier 라우팅 ✅ |
| A3 | 틀린 비번으로 로그인 | 에러 문구 표시 | INVALID_CREDENTIALS 연동 |
| A4 | `planning@test.local` 로그인 | `/planning` "경영기획팀"으로 이동 | **STRATEGY↔planning 변환** ✅ |
| A5 | 회원가입(아무 이메일) | PENDING 락 화면 | signup + 승인대기 연동 |
| A6 | (확정타) DevTools → Network 탭 | `/api/v1/auth/login` 요청이 **`localhost:8080`으로 나가고 200** | "진짜 백엔드 호출" 확정 |

→ **A2 + A6이 뜨면 "FE↔BE auth 연동 성공"**이 증명된다. (A6이 핵심 — mock이 아니라 실 백엔드로 갔다는 증거)

## 5. 검증 시나리오 B — 새 엔드포인트

**지금(화면 mock이라 API 레벨로):** 백엔드가 맞게 주는지 터미널로 확인

```bash
# 토큰 받기
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"purchasing@test.local","password":"test1234!"}' | grep -oE '"access_token":"[^"]*"' | head -1 | sed 's/.*:"//; s/"//')

# 리스크 목록 (4건)
curl -s http://localhost:8080/api/v1/risk-events -H "Authorization: Bearer $TOKEN" | head -c 200

# 공개 지도 (토큰 없이 4건)
curl -s http://localhost:8080/api/v1/public/risk-board | head -c 200
```

→ `success:true` + 데이터 나오면 **백엔드 구현 OK** (여긴 이미 검증됨).

**영진님 연결 후(화면으로):**

| 화면 동작 | 기대 | 증명 |
| --- | --- | --- |
| 로그인 후 구매팀 대시보드 | 리스크 4건 카드가 뜸 | `/risk-events` 화면 연동 |
| 루트 `/` 공개 지도 | 지도에 마커가 뜸 | `/public/risk-board` 연동 |
| 계약 목록 화면 | 계약 29건 | `/contracts` 연동 |

이건 영진님이 `fetchRiskEvents` 등을 실 API로 바꾼 뒤라야 mock이 아닌 실데이터가 뜬다. (지금 보면 mock 4건이 떠서 "되는 것처럼" 착각할 수 있으니, Network 탭에서 실제 요청이 `localhost:8080`으로 나가는지 꼭 확인)

---

## 결론 — 지금 당장 할 것

1. 백엔드 켜기(2번) → 헬스 UP 확인
2. 진짜 프론트 켜기(3번, `VITE_API_BASE_URL` 설정)
3. **시나리오 A(auth) 화면 검증** — 여기까지가 지금 "FE↔BE 연동"으로 증명 가능한 부분
4. 새 엔드포인트는 5번 API 레벨로 확인 (화면은 영진님 연결 후)
