# 로딩 시간 최적화 — 구현 정리 (2026-08-15)

브랜치: 두 저장소 모두 `perf/loading-speed` (backend_merge · frontend)
배포 대상: https://d2qeq07s1pqlri.cloudfront.net (프론트 S3+CloudFront, 백엔드 ECS)

## 1. 문제 진단 (실측 기반)

로컬·배포 양쪽에서 페이지 로딩·로그인이 느렸다. 실측 결과 원인은 인프라가 아니라 코드 세 곳:

| # | 병목 | 실측 |
|---|---|---|
| 1 | DB: `raw_events`(9.4천 행, 본문 평균 4.2천 자)에 대한 **정규식 전체 스캔** | 요청당 **1.1~1.2초**, 대시보드 한 화면이 2~3회 유발 |
| 2 | 번들: 코드 스플리팅 없음 — 27개 페이지 + three.js(570KB)를 첫 화면 전에 전량 다운로드 | 첫 JS **1.36MB** (gzip ~350KB) |
| 3 | 직렬 대기 + 무캐싱: JS 전량 → refresh → /auth/me → 데이터 12요청 순차, staleTime 0 | 화면 이동마다 전체 재요청 |

## 2. 구현 내용 (Part A~E)

### Part A — 백엔드: 정규식 스캔 제거 ✅ (효과 최대)

- **V38 마이그레이션**: `raw_events.material_matched` **generated column**(STORED) + 부분 인덱스.
  자재 키워드 매칭을 조회 시마다 계산하지 않고 행 저장 시점에 굳힌다. 앱 write 경로 무변경(백필·갱신 자동).
- `RawEventRepository` 3개 쿼리(`findSupplyChainNews` / `countSupplyChainNews` / `findLatestSupplyChainCollectedAt`)를
  정규식 → `material_matched` 컬럼 기반으로 교체. 세 번째 쿼리는 Executive/Planning/MaterialRisk 3개 서비스가
  "기준 시각"으로 호출하므로 효과가 가장 넓게 퍼진다.
- **키워드 동기화 가드 테스트**(`MaterialKeywordSyncTest`): V38 SQL의 정규식 키워드 집합과
  Java `MATERIAL_KEYWORDS`가 어긋나면 테스트가 즉시 깨진다. 키워드 추가 시 새 마이그레이션으로 컬럼 재정의 필요(양쪽 주석 명시).
- 결과 (warm, 로컬 실측):

| 쿼리 | 전 | 후 |
|---|---|---|
| 뉴스 목록 | 1,210 ms | **19 ms** (~64배) |
| 기준 시각 | 1,100 ms | **7 ms** (~157배) |
| 실 HTTP news-feed | ~1.2 s | **25 ms** (~48배) |

- **기능 동일성 검증**: 마이그레이션 전후 전체 9,472행 대조 — 매칭 결과 불일치 0건. 백엔드 테스트 220건 통과.

### Part B — 프론트: 번들 분할 ✅

- `routes.tsx` 27개 페이지 전부 `React.lazy` + `<Suspense fallback={DashboardBootstrapSkeleton}>`.
- three.js(570KB, 헤더 WebGL 로고 하나 때문에 전 페이지에 실리던 것)를 `PrismHomeMarkCanvas`로 분리해
  lazy 로드 — 첫 페인트 이후 비동기로 온다. 폴백은 같은 자리 크기의 정적 SVG.
- `vite.config.ts` `manualChunks`: vendor 대분류(react / charts / map / three) 명시 분리 — 재배포 시 캐시 적중 향상.
- **재배포 청크 404 방어**: `main.tsx`에 `vite:preloadError` 리스너 — 재배포 직후 옛 페이지가 사라진 해시의
  청크를 요청해 죽는 회귀를 1회 자동 새로고침으로 복구(sessionStorage 가드로 무한 리로드 방지).
- 결과: 첫 진입 JS **1.36MB → ~505KB** (gzip ~157KB), 임계 CSS 219KB → 4.2KB. 나머지는 페이지별 청크로 지연 로드.

### Part C — 프론트: 캐싱·부트스트랩 (일부 적용)

- **C-1 ✅** `App.tsx` QueryClient `staleTime: 60_000` — 화면을 옮겼다 돌아올 때 재요청 억제.
  기존 관례(`retry:false`, `refetchOnWindowFocus:false`)는 유지.
- **C-2 보류(사용자 결정)** — 수동 `useEffect+fetch` 화면들의 useQuery 전면 이관. Part A로 API가 수십 ms가 되어
  체감 이득이 급감했으므로 우선순위 낮음. 착수 시 기능 보존 규칙(응답 부수효과는 useEffect(data)로 이관,
  reloadKey류 수동 재조회는 invalidateQueries로 치환, 업로드 성공 시 관련 조회 invalidate)을 반드시 지킨다.
- **C-3 ✅ (2026-08-15 추가 구현)** — refresh 응답에 org_tier·username을 포함시켜(추가 DB 조회 0 —
  refresh()가 이미 사용자를 로드) F5 세션 복원이 **/auth/me 없이 refresh 한 번으로** 끝난다.
  구버전 백엔드와 섞이는 배포 순서를 대비해 필드가 없으면 /auth/me 폴백(AuthProvider). 실측: F5 시 auth 왕복 2→1회.

### Part E — 추가 단축 ✅

- **E-1** Spring `server.compression`(gzip, `application/json` 한정, min 1KB) — 뉴스 목록 2,566B → 743B(-71%).
  SSE 없음 확인, 파일 다운로드(PDF/CSV)는 mime 한정으로 제외.
- **E-2** 로그인 화면 유휴 시점(`requestIdleCallback`)에 계층 대시보드 청크 프리로드 — 로그인 직후 전환 체감 0에 근접.
- **E-3 (제외 확정)** API 오리진 preconnect — 배포 프론트는 API가 **같은 오리진**(CloudFront `/api` 라우팅)이라 무의미.
- **E-4 (대기)** 잔여 느린 API 개별 격파 — 배포 후 실측으로 대상 선정(§6).

### 기능 무손실 점검 (2026-08-15)

- 백엔드 전체 테스트 통과(refresh DTO 변경 포함). 브라우저 시나리오 7종 통과: F5 복원(마스터·일반 계정,
  refresh 1회만), 기간 탭 즉시 반응, 뉴스 페이지네이션·화살표 잠금, 로그아웃 후 세션 부활 없음,
  일반 계정에서 마스터 탭 숨김, 구버전 백엔드 폴백 경로 보존(코드 검토).
- 폴링 실간격(60초/5분)·탭 복귀 갱신은 자동화 브라우저가 hidden 상태라 미관측 — 두 키가 같은 훅·같은
  focus 리스너를 쓰므로 구조상 동일. 실브라우저에서 대시보드 5분 열어두고 Network로 확인 가능.

## 3. CloudFront 배포 기준 점검 (2026-08-15)

**결론: 코드는 배포 기준으로 유효. 파이프라인 빈틈 1개를 수정했다.**

- 프론트 배포(`deploy-frontend.yml`): main push → `npm run build`(같은 vite.config) → S3 sync → invalidation.
  → Part B·C-1·E-2는 **머지만 하면 자동 반영**.
- 백엔드 배포(`deploy-spring.yml`): main push → ECR 빌드 → ECS 배포, Flyway가 기동 시 V38 자동 적용.
  → Part A·E-1도 **머지만 하면 자동 반영**.
- **수정한 빈틈**: `aws s3 sync`에 `--cache-control`이 없어 해시 청크가 브라우저 장기 캐시 안 됨.
  → 해시 자산 `public,max-age=31536000,immutable` / `index.html` `no-cache`로 분리 업로드 (커밋 완료).

### 콘솔에서 직접 확인할 것 (레포 밖 — 코드로 검증 불가)

- [ ] CloudFront 배포 `EKDLK3C0COU4Y`의 **"Compress objects automatically"** 켜짐 여부.
      꺼져 있으면 정적 자산이 무압축(~505KB)으로 전송된다. 켜면 gzip(~157KB).
- [ ] `/api/*` 비헤이비어 캐시 정책이 `Accept-Encoding`을 오리진에 전달하는지
      (기본 CachingDisabled+AllViewerExceptHostHeader면 전달됨) — Spring gzip이 CDN 너머로 먹기 위한 조건.

### 배포 후 검증 커맨드

```bash
curl -sI https://d2qeq07s1pqlri.cloudfront.net/index.html                  # Cache-Control: no-cache
curl -sI https://d2qeq07s1pqlri.cloudfront.net/assets/<해시청크>.js         # immutable + Content-Encoding
curl -sI -H "Accept-Encoding: gzip" "https://d2qeq07s1pqlri.cloudfront.net/api/v1/public/news-feed?limit=20"  # gzip
```

브라우저: 재방문 시 Network 탭에 청크가 `(disk cache)`로 떠야 캐시 헤더가 작동하는 것.

## 4. 롤백 경로

| Part | 1차 롤백 | 비고 |
|---|---|---|
| A | Java 커밋만 revert (DB 그대로) | V38은 추가만 하는 비파괴 마이그레이션 — 구코드도 새 스키마에서 동작(리허설 검증 완료). 옛 정규식 쿼리는 `[ROLLBACK]` 주석으로 보존 |
| A (DB까지) | `db/rollback/R38__…sql` 수동 실행 → 커밋 revert | Flyway 경로 밖이라 자동 실행 안 됨. 순서 지킬 것(스크립트 먼저) |
| B | 커밋 revert 또는 routes.tsx 주석 토글 | 정적 import 원본 주석 보존 |
| C-1/E-1 | 한 줄/한 블록 주석 처리 | 각 위치에 [ROLLBACK] 표기 |
| CI 캐시 헤더 | 스텝을 원래 3줄 sync로 되돌림 | 독립 커밋 |

## 5. 커밋 목록 (perf/loading-speed, 실제 이력 기준)

**backend_merge** (main 이후 3개)
- `6371ff8` `perf: 뉴스 조회 생성 컬럼 최적화 + JSON gzip 압축` — Part A(V38+쿼리+가드 테스트) + E-1
- `6bfffe6` `feat(auth): 시연용 마스터 계정 - 1·2·3계층 대시보드 전체 열람` — V39, 성능과 무관(시연 편의)
- `7b3a8b8` `perf(auth): refresh 응답에 org_tier·username 포함 - 부트스트랩 왕복 1회 절감` — C-3 백엔드 쪽 + 문서

**frontend** (main 이후 7개)
- `f98e78c` `perf: 코드 스플리팅 + 쿼리 캐시 + 청크 프리로드로 로딩 단축` — Part B + C-1 + E-2
- `06348a1`·`ca23a4e` `feat(auth): 시연용 마스터 계정 …` — 전 계층 접근 + 헤더 계층 전환 탭
- `00217d1` `feat(auth): 마스터 계정 계층 전환 탭 + 경영기획 알림 벨, ci: S3 캐시 헤더` — §3 캐시 헤더 수정 포함
- `9b66d9c` `fix: 관리자 가입 관리 표 가로 스크롤 제거`
- `603f96d` `perf(auth): F5 세션 복원을 refresh 단일 왕복으로 단축 (me 폴백 유지)` — C-3 프론트 쪽
- `dda6810` `perf(refresh): 지도·환율·가격 폴링 5분 차등화 (뉴스 60초 유지)` — §6-1

## 6. 남은 개선 후보 및 처리 현황

1·4는 2026-08-15에 구현 완료했다. 나머지는 **배포 후 Network 탭 실측으로 병목이 확인될 때만 착수**한다
(전 API 로컬 실측 24~123ms — executive 대시보드 123ms 외 전부 100ms 미만이라, 실측 없이 미리 고치는 것은 과잉).

1. ~~자동 갱신 주기 차등화~~ **✅ 완료(2026-08-15)** — `LIVE_REFRESH_SLOW_INTERVAL_MS`(5분) 추가.
   지도·수입의존도·환율·가격(구매팀·공개 대시보드)과 2계층 planning 쿼리 전체를 5분으로, 뉴스 목록·건수·마퀴는
   60초 유지. 탭 복귀 시 즉시 갱신은 두 주기 모두 동일하게 동작(focus 리스너)해 복귀 신선도는 변화 없음.
2. **지도·차트 뷰포트 지연 로딩** — leaflet 지도, recharts 차트를 화면에 보일 때(IntersectionObserver) 마운트.
   첫 페인트가 더 빨라지지만, 이미 라우트 분할로 해당 페이지에서만 로드되므로 한계 효과.
3. **C-2 useQuery 전면 이관** — 재방문 캐시 적중. 원계획의 기능 보존 규칙 필수.
4. ~~인증 복원 refresh→me 통합~~ **✅ 완료(2026-08-15)** — §2 C-3 참조. F5 복원 auth 왕복 2→1회.
5. **E-4: API별 실측 후 SQL 개별 격파** — planning/executive 대시보드 API가 배포에서 수백 ms 이상이면
   EXPLAIN ANALYZE → 인덱스/사전계산으로 대응.
6. **ECS 태스크 수 확대** — Spring이 1 태스크라면 동시 요청 몰릴 때 지연 가능. 코드가 아니라 인프라 설정
   (배포 담당자 협의). 위 1~5로도 부족할 때 검토.

## 7. 범위 밖 이슈 (로딩과 별개 — 별도 트랙)
- **탭 간 로그인 계정 혼재**: 한 브라우저의 두 창에서 다른 계정으로 로그인하면, access token이 탭 메모리에·
  refresh 쿠키는 브라우저 공용에 있어 이전 창이 이전 계정 화면을 유지한다. "마지막 로그인만 유효" 정책을 원하면
  백엔드(새 로그인 시 기존 refresh 세션 무효화) + 프론트(BroadcastChannel로 타 창 로그아웃) 동시 수정 필요.
  **로딩 성능과 무관한 세션 정책 이슈**이므로 이 문서가 아니라 별도 과제로 다룬다.
