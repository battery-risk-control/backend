# 🗺️ FE↔BE 연동 검증 로드맵 (backend_merge + frontend 기준, 2026-07-28 정정본)

> 작업 대상 고정: 백엔드 `C:\aivleschool\bigproject\backend_merge`, 프론트 `C:\aivleschool\bigproject\frontend`.
> 터미널은 **PowerShell** 기준.

## ⚠️ 먼저 — 지금 "화면으로" 검증되는 범위

- **로그인/회원가입 (auth)** → 실 백엔드 연결됨 → **화면 검증 O** ✅
- **공개 대시보드 (`/`)** → 실 백엔드(`/public/risk-board`) 연결됨 → **화면 검증 O** ✅ (실측 확인)
- **팀별 대시보드/리스크/계약/브리핑** → 프론트가 **아직 mock** → 화면엔 mock이 뜸.
  실 API 전환 전까지는 **API 레벨(curl)로만** 백엔드 응답을 검증.

> ❗`npm run dev`(기본)는 **항상 mock**이다. 실 백엔드 연결은 **`npm run dev:live`**로만 된다(아래 3번).

---

## 1. FE 연동용 API 목록

| 화면/기능 | 엔드포인트 | 인증 | 현재 데이터 |
| --- | --- | --- | --- |
| 로그인/회원가입/내정보 | `POST /auth/login`·`/auth/signup`, `GET /auth/me` | me만 Bearer | 실계정(시드) |
| 리스크 목록(구매팀) | `GET /risk-events` | Bearer | placeholder 4 |
| 대시보드 | `GET /dashboard/summary`·`/materials`·`/import-dependency` | Bearer | ERP 실데이터(material 11·supplier 19), severity 0 |
| 계약 목록 | `GET /contracts` | Bearer | 실데이터 29 (⚠️ `size` ≤ 100) |
| 브리핑 | `GET /briefings`·`/briefings/{id}` | Bearer | 0(분석 전) |
| **공개 지도** | `GET /public/risk-board` | **무인증** | placeholder 4 |

---

## 2. 백엔드 서버 켜기 (backend_merge)

```powershell
cd C:\aivleschool\bigproject\backend_merge

# (옛 battery-risk-mvp-starter 컨테이너가 떠 있으면 먼저 내린다 — 이름·포트 충돌 방지)
# cd C:\aivleschool\bigproject\battery-risk-mvp-starter; docker compose down; cd C:\aivleschool\bigproject\backend_merge

# ERP 데이터까지 적재하며 기동 (PowerShell 환경변수 방식)
$env:ERP_SEED_ENABLED="true"; $env:AUTH_TEST_SEED_ENABLED="true"; docker compose up -d spring
```

- `up -d spring`은 spring + 의존(postgres·fastapi·chroma)만 켠다(내부 frontend 서비스는 안 켜짐).
- **PowerShell에선 `VAR=값 명령`(bash식)이 안 된다.** 반드시 `$env:VAR="값"; 명령` 형식.
- ERP 적재 사전조건(이미 `docker-compose.yml`에 반영됨): spring 서비스에
  마운트 `./data/ERP_data/spring-csv:/app/erp-seed:ro` + env `ERP_SEED_DIRECTORY: /app/erp-seed`.
- **헬스 확인**: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
- **적재 확인**: `docker compose logs spring | Select-String "ERP CSV seed"`
  → `F6 ERP CSV seed completed: 10 files` 뜨면 성공 (컨테이너가 완전히 뜬 뒤 확인).

---

## 3. 프론트엔드 서버 켜기 (⭐ 진짜 프론트, live 모드)

```powershell
cd C:\aivleschool\bigproject\frontend
npm install          # 최초 1회 (이미 완료했다면 생략)
npm run dev:live     # ⚠️ 'npm run dev'가 아니라 'dev:live' — 이것만 실 백엔드로 붙는다
```

- `.env.live`(백엔드 주소 `VITE_API_BASE_URL=http://localhost:8080`)는 **이미 생성돼 있음** → 새로 만들 필요 없음.
- `dev:live`는 포트 **5173 고정**(백엔드 CORS가 5173만 허용). 5173이 이미 점유돼 있으면 에러가 난다.
  → `netstat -ano | findstr :5173`으로 PID 확인 후 `taskkill /PID <PID> /F`로 정리.
- 터미널에 `VITE ... live  ready` + `Local: http://localhost:5173/` 뜨면 성공.
- 브라우저에서 `http://localhost:5173` 접속.

> 참고: 옛 로드맵의 `.env.local` + `npm run dev`는 이 repo에선 동작하지 않는다.
> `npm run dev`는 설계상 항상 mock이며 env 파일을 읽지 않는다. 파일명은 `.env.live`, 명령은 `dev:live`.

---

## 4. 검증 시나리오 A — auth + 공개 대시보드 (지금 화면으로 검증 가능) ✅

| # | 화면에서 하는 동작 | 기대 결과 | 증명되는 것 |
| --- | --- | --- | --- |
| A1 | `/` 접속 | 공개 대시보드가 로그인 없이 뜸 (지도 마커 4개·뉴스) | 공개 라우트 + `/public/risk-board` 실연결 |
| A2 | `/auth`에서 `purchasing@test.local` / `test1234!` 로그인 | **`/purchasing` "구매팀 대시보드"로 이동** | 실 FE→실 BE 로그인 + 토큰 + org_tier 라우팅 |
| A3 | 틀린 비번으로 로그인 | 에러 문구 표시 | INVALID_CREDENTIALS 연동 |
| A4 | `planning@test.local` / `test1234!` 로그인 | `/planning` "경영기획팀"으로 이동 | STRATEGY↔planning 변환 |
| A5 | 회원가입(아무 이메일) | PENDING 락 화면 | signup + 승인대기 연동 |
| A6 | (확정타) DevTools → Network 탭 | `/api/v1/auth/login` 요청이 **`localhost:8080`으로 나가고 200** | "진짜 백엔드 호출" 확정 |

→ **A2 + A6이 뜨면 "FE↔BE auth 연동 성공"** 증명. (A6이 핵심 — mock이 아니라 실 백엔드로 갔다는 증거)

---

## 5. 검증 시나리오 B — 나머지 엔드포인트 (화면은 아직 mock → API 레벨로)

토큰을 받아 백엔드 응답만 확인 (PowerShell):

```powershell
$body = '{"email":"purchasing@test.local","password":"test1234!"}'
$login = Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Body $body
$token = $login.data.access_token
$H = @{ Authorization = "Bearer $token" }

# 계약 (29건, size는 100 이하로)
(Invoke-RestMethod -Uri "http://localhost:8080/api/v1/contracts?page=0&size=100" -Headers $H).data.content.Count

# 대시보드 집계
Invoke-RestMethod -Uri http://localhost:8080/api/v1/dashboard/summary -Headers $H | ConvertTo-Json -Depth 5

# 공개 지도 (토큰 없이 4건)
(Invoke-RestMethod -Uri http://localhost:8080/api/v1/public/risk-board).data.Count
```

→ `success:true` + 데이터가 나오면 **백엔드 구현 OK**.

**팀 대시보드/계약 화면이 실데이터로 뜨려면** 프론트가 해당 화면의 fetch를 실 API로 바꿔야 함(현재 mock).
바뀐 뒤에는 Network 탭에서 요청이 `localhost:8080`으로 나가는지로 확정.

---

## 결론 — 순서 요약

1. 백엔드 켜기(2번) → health UP + ERP seed 로그 확인
2. 진짜 프론트 켜기(3번) → **`npm run dev:live`** (`.env.live` 이미 있음)
3. 시나리오 A(auth + 공개 대시보드) 화면 검증 — 지금 화면으로 증명 가능한 범위
4. 나머지는 5번 API 레벨로 확인 (화면 연결은 이후 작업)

---

## 부록 — 옛 로드맵에서 바뀐 점 요약

| 구분 | 옛 로드맵 | 지금 (정정) |
| --- | --- | --- |
| 백엔드 경로 | `battery-risk-mvp-starter` | `backend_merge` |
| 백엔드 실행 | `AUTH...=true docker compose up`(bash식) | PowerShell `$env:...="true"; docker compose up -d spring` |
| 옛 컨테이너 | — | 먼저 `docker compose down` (이름·포트 충돌) |
| ERP 데이터 | "실데이터 29"(옛 볼륨) | 새 DB → compose 마운트+플래그로 직접 적재(완료, 29건) |
| 프론트 env 파일 | `.env.local` | `.env.live` (이미 생성됨) |
| 프론트 실행 | `npm run dev` (항상 mock) | `npm run dev:live` |
| 화면 검증 범위 | "auth만 가능" | auth + 공개 대시보드 둘 다 실연결(실측), 팀 대시보드는 mock |
| 계약 API | — | `/contracts` size ≤ 100 (초과 시 500) |
