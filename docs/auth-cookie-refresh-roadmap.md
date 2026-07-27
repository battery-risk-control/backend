# 인증 영속화 로드맵 — silent refresh + httpOnly 쿠키

> **상태: 합의 완료, 착수 지연(deferred).** FE·BE가 이 설계에 합의했으나, 데모 단계에선 하지 않는다.
> FE가 로그인 유지(silent refresh)를 실제로 붙이는 시점에 맞춰 BE 쿠키 전환을 **같은 시점에 함께** 배포한다.
> 작업 착수 시 백엔드 구현 상세는 세션 작업 칩 `task_39107329` 참고.

## 배경 — 왜 이 방향인가

- auth 외 모든 API는 Bearer 토큰 필요. 현재 `access_token`은 **메모리 전용**이라 새로고침 시 소실 → 401 → 로그인 화면으로 튕김.
- localStorage 저장은 **XSS로 토큰 탈취** 위험(JS가 읽을 수 있음)이라 기각.
- 결론: `access_token`은 메모리 유지하되, 새로고침 시 **`refresh_token`으로 조용히 재인증(silent refresh)**. 이때 `refresh_token`을 **httpOnly 쿠키**에 두면 JS가 접근 못 해 XSS에 안전하고, 브라우저가 자동 전송해 FE 저장 코드도 불필요.
- **핵심 전제**: silent refresh가 "localStorage와 다른 의미(안전)"를 가지려면 `refresh_token`이 반드시 **httpOnly 쿠키**여야 한다. body(JSON)로 주면 결국 어딘가 저장해야 해서 근본 문제가 남는다(오히려 14일짜리라 더 위험).

## 0. 언제 시작하나 (트리거)

- FE가 "로그인 유지"를 실제로 붙이기로 한 시점 (데모 → 배포 전환, 또는 새로고침 로그아웃이 실사용에 불편해질 때).
- ⚠️ **데모 단계에선 이 로드맵 전체를 하지 않는다** — 메모리 유지가 정답. 배포/서비스 수준으로 갈 때만.

## 1. 착수 전 결정 (제일 먼저)

- **배포 도메인 구조** — `SameSite`/`Secure`/CORS를 좌우:
  - FE·BE **같은 도메인**(nginx 뒤 한 곳) → `SameSite=Lax` (간단)
  - **다른 도메인** → `SameSite=None; Secure` + CORS `allowCredentials`(이미 true)
- refresh 쿠키 이름·범위 (예: `refresh_token`, `Path=/api/v1/auth`)
- 토큰 수명 유지 (access 1h / refresh 14d)

## 2. 백엔드 작업 (담당: minji — 별도 브랜치, ⚠️ main 머지 금지)

1. **login**: `refresh_token`을 body 대신 `Set-Cookie(HttpOnly, Secure, SameSite, Path)`로 내린다. `access_token`은 지금처럼 body 유지.
2. **`POST /auth/refresh`**: `@CookieValue`로 쿠키에서 `refresh_token`을 읽어 새 access 발급 → (선택) refresh 회전.
3. **`POST /auth/logout`**: 쿠키 `Max-Age=0`으로 삭제 + 기존 블랙리스트 유지.
4. **`Secure` 플래그 env/프로파일 분기** (dev=false — 로컬 http에선 Secure 쿠키가 안 붙음 / prod=true — https).
5. **`AuthFlowTest` 갱신**: 현재 `jsonPath("$.data.refresh_token").exists()`와 body 기반 `/auth/refresh` 호출을 검증하므로, 쿠키 방식으로 고쳐야 빌드 통과.

관련 파일: `controller/AuthController.java`, `service/AuthService.java`, `dto/auth/RefreshRequest.java`(폐기 검토), `dto/auth/LoginResponse.java`(refresh 필드 제거 검토), `config/SecurityConfig.java`, `test/.../AuthFlowTest.java`

**현재 상태(전환 전)**: 로그인 응답 body에 `refresh_token`이 실려 오고, `/auth/refresh`도 `RefreshRequest(@RequestBody)`로 받는다.

## 3. 프론트 작업 (담당: 영진)

1. **AuthProvider 확장**: 지금 `orgTier`만 담는데 `access_token`(메모리)도 담도록.
2. **앱 부팅 시 silent refresh**: 화면 그리기 전 `POST /auth/refresh`(`credentials:'include'`) → 새 access 받아 메모리 채움. 성공=로그인 유지, 실패(refresh 만료 등)=로그인 화면.
3. **RequireAuth 로딩 상태**: 재인증 중엔 "확인 중" 표시 후 성공/실패 분기.
4. **login/refresh 호출에 `credentials:'include'`** 추가.
5. **logout 시** 서버 `/auth/logout` 호출(쿠키 무효화) + 메모리 클리어.

## 4. 함께 배포 (핵심)

BE 쿠키 브랜치 + FE silent refresh 브랜치를 **같은 시점에 머지/배포**한다. 한쪽만 나가면 `/refresh` 계약이 어긋나 깨진다. (이게 "같은 시점"이 합의된 이유)

## 5. 검증 (acceptance 체크리스트)

- [ ] 로그인 후 **새로고침 → 로그인 유지됨**
- [ ] `document.cookie`에 refresh 안 보임 (**HttpOnly 확인**)
- [ ] refresh 만료 후 새로고침 → 로그인 화면
- [ ] 로그아웃 후 새로고침 → 로그인 화면 (쿠키 삭제 확인)
- [ ] 새 탭 열기 → silent refresh로 로그인 상태 복원
- [ ] 로컬(http) Secure off로 쿠키 붙음 / 배포(https) Secure on

## 6. 안전장치

- **별도 브랜치**로 작업 → 실패해도 현재(메모리) 방식 영향 0.
- `access_token`은 계속 **Bearer 헤더**(자동 전송 아님) → 일반 API는 CSRF 무관. 쿠키는 `/auth/refresh`에만 쓰이고 `SameSite`로 CSRF 완화. (현재 `SecurityConfig`의 CSRF disable 유지 가능)

## 7. 미리 짜면 안 되는 이유 (지금 하지 않는 근거)

- 런타임은 안 깨지지만(현재 FE가 `refresh_token`·`/auth/refresh`를 안 씀), **`AuthFlowTest`가 깨지고**, 쿠키 흐름 전체는 **FE silent refresh가 있어야 end-to-end 검증** 가능하며, `SameSite`/도메인 디테일은 **배포 토폴로지 확정 후에야** 제대로 정할 수 있어 재작업 위험이 있다. → FE와 같은 시점에 하는 것이 재작업 없이 한 번에 끝나는 길.
