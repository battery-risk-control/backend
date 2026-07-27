# S15 서비스 장애 시나리오 검증 기록

- 검증일: 2026-07-25
- 대상: `docker-compose.yml`로 통합한 5개 서비스 전체 스택 (postgres·chroma·fastapi·spring·frontend)
- 관련 수정 커밋: `25af6b3` (nginx 프록시 연결 타임아웃 단축)

## 1. 목적

"서비스 하나가 죽었을 때 시스템이 어떻게 반응하는가"를 실제로 서비스를 내려 확인한다. 세 가지를 본다.

- **영향 범위(blast radius)**: 한 서비스가 죽으면 어디까지 망가지고 어디는 멀쩡한가
- **우아한 실패(graceful degradation)**: 죽었을 때 깔끔한 에러를 *빨리* 주는가, 아니면 500·무한 대기·전체 크래시인가
- **복구(recovery)**: 다시 살리면 전체 재시작 없이 자동 복구되는가

## 2. 방법

- 접속 경로는 브라우저 관점 그대로: nginx(`localhost:5173`) → spring → postgres/fastapi → chroma
- 프로브 4종:
  - 로그인 `POST /api/v1/auth/login` (postgres 필요)
  - 대시보드 `GET /api/v1/dashboard/summary` (인증 + postgres)
  - RAG 검색 `POST /api/v1/rag/search` (spring → fastapi → chroma)
  - 정적 프론트 `GET /` (nginx만)
- 절차: 기준선 확인 → `docker compose stop <서비스>` → 관찰 → `docker compose start <서비스>` → 복구 확인
- 응답은 HTTP 코드와 응답 시간을 함께 측정(행 여부 판별용). 코드 `000`은 클라이언트 타임아웃(=행)을 의미한다.

## 3. 기준선 (정상 상태)

| 프로브 | 결과 |
| --- | --- |
| 로그인 | 200, 0.07초 |
| 대시보드 | 200, 0.02초 |
| RAG 검색 | 200, 0.11초 |
| 정적 프론트 | 200, 0.005초 |

## 4. 시나리오별 결과

| 내린 서비스 | 영향 없음 | 영향받음 | 실패 방식 | 컨테이너 상태 | 복구 |
| --- | --- | --- | --- | --- | --- |
| **fastapi** | 로그인·대시보드·프론트 | RAG | 503 `FASTAPI_UNAVAILABLE`, ~5초 | spring healthy 유지 | 재기동만으로 자동 |
| **chroma** | 로그인·대시보드·프론트 | RAG | 503 `VECTOR_STORE_UNAVAILABLE`, ~4초. fastapi `/health`도 503으로 DOWN 보고 | fastapi 유지 | 재기동만으로 자동 |
| **postgres** | 정적 프론트 | 로그인·대시보드 등 DB 의존 전부 | **~30초 행** 후 실패 (미수정 갭) | spring 살아있음(재시작 0회) | 재기동만으로 자동 |
| **spring** | 정적 프론트 | 모든 `/api` | 수정 전 ~60초 행 → **수정 후 5.0초 504** | — | 재기동만으로 자동 |

## 5. 검증이 증명한 것

**설계가 견고한 부분**

- 영향 범위가 좁다 — AI 스택(fastapi/chroma)이 죽어도 로그인·대시보드는 정상 동작한다.
- 관측성이 좋다 — 어느 계층이 죽었는지 에러 코드로 구분된다(`FASTAPI_UNAVAILABLE` vs `VECTOR_STORE_UNAVAILABLE`).
- 느슨한 결합 — 재시작루프 없이 살아있고, 죽은 서비스만 다시 켜면 전체 재시작 없이 복구된다.
- 정적 프론트는 백엔드와 독립적이다 — 백엔드가 모두 죽어도 페이지 자체는 뜬다.

**발견한 갭 2가지 (둘 다 "빠른 에러가 아니라 느린 행")**

| 갭 | 원인 | 상태 |
| --- | --- | --- |
| spring 다운 시 nginx가 최대 60초 행 | nginx 기본 `proxy_connect_timeout` 60초 | **수정 완료** (`25af6b3`). `proxy_connect_timeout 5s`로 5.0초 504 확인. `proxy_read_timeout`/`proxy_send_timeout`은 문서 업로드 등 오래 걸리는 정상 작업 보호를 위해 120초로 유지 |
| postgres 다운 시 30초 행 | HikariCP 기본 `connection-timeout` 30초 | **미수정(의도적 보류)**. 사유는 아래 |

### postgres 30초 행을 아직 수정하지 않은 이유

수정 자체는 `application.yml`의 `spring.datasource.hikari.connection-timeout`을 5000으로 낮추는 2줄이면 되고, 언제든 안전하게 적용 가능하다. 다만 우선순위가 nginx 갭보다 낮다.

- **UX 개선 폭이 상대적으로 작다.** nginx/fastapi/chroma 장애는 *다른 기능은 멀쩡한데 일부만 실패*하는 상황이라, 빠른 에러가 중요하다. 반면 postgres가 죽으면 어차피 시스템 전체가 사용 불가라, 5초든 30초든 사용자에게는 "전체 다운"으로 동일하게 느껴진다.
- **부하와의 트레이드오프가 있다.** `connection-timeout`은 풀에서 커넥션을 얻기까지의 대기 시간이다. 5초로 낮추면 트래픽이 몰려 풀이 잠깐 고갈됐을 때도 5초 만에 실패할 수 있다. MVP 규모에서는 문제없지만, nginx의 `connect_timeout`(연결은 즉시 되거나 죽었거나 둘 중 하나라 단점이 없음)과 달리 "부하 상황에서의 실패"라는 성격이 있어 반사적으로 낮추기보다 의도적으로 정할 값이다.

정리하면 "고치면 안 되는" 갭이 아니라 "지금 급하지 않아 후속으로 미룬" 갭이다.

## 6. 재현 방법

```bash
# 예: fastapi 장애 재현
docker compose stop fastapi
curl -m 35 -w "\n%{http_code} %{time_total}s\n" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"query":"공급 중단 시 대체","filters":{"contract_id":2},"top_k":3}' \
  http://localhost:5173/api/v1/rag/search
docker compose start fastapi
```

`stop` 대상만 `chroma`/`postgres`/`spring`으로 바꾸면 각 시나리오를 재현할 수 있다. 정상 경로 프로브는 로그인·대시보드·정적 프론트를 함께 확인한다.

## 7. 결론

전체 스택은 한 서비스 장애가 전체로 번지지 않고(영향 범위가 좁고), 계층별로 명확한 에러를 주며, 개별 재기동으로 자동 복구된다. 남은 개선점은 postgres 다운 시의 30초 행 하나이며, 이는 의도적으로 후속 과제로 남겨두었다.
