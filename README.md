# 배터리 원자재 공급망 리스크 관제 — Backend

배터리 핵심 원자재(리튬·코발트·니켈·구리·흑연·희토류 등)의 공급망 리스크를 실시간 뉴스·ERP·계약 데이터로 탐지하고, LLM 멀티에이전트로 브리핑을 생성해 계층별 대시보드로 제공하는 백엔드입니다.

- **Spring Boot** — 기업 업무·API의 안정적 중심(대시보드/리스크/계약/브리핑 조회, ERP·계약 관리, 분석 오케스트레이션, PostgreSQL 읽기·쓰기)
- **FastAPI** — AI·ML·RAG 처리 전담(XGBoost 트리아지, LLM 추출, Severity 규칙 엔진, 계약서 RAG, LangGraph 멀티에이전트 브리핑)

프론트엔드(React)는 **Spring Boot만** 호출하고, FastAPI는 외부에 직접 노출되지 않습니다.

---

## 목차
1. [아키텍처](#1-아키텍처)
2. [기술 스택](#2-기술-스택)
3. [빠른 시작 — Docker](#3-빠른-시작--docker)
4. [환경 변수(.env)](#4-환경-변수env)
5. [서비스·포트](#5-서비스포트)
6. [디렉터리 구조](#6-디렉터리-구조)
7. [데이터 적재(Seed)](#7-데이터-적재seed)
8. [로컬 개발(Docker 없이)](#8-로컬-개발docker-없이)
9. [API 개요](#9-api-개요)
10. [데이터 파이프라인](#10-데이터-파이프라인)
11. [DB 마이그레이션](#11-db-마이그레이션)
12. [테스트](#12-테스트)

---

## 1. 아키텍처

```
React (프론트엔드)
  │
  ▼
Spring Boot ──────────────────────────────── PostgreSQL (업무·이벤트·AI 결과 읽기·쓰기)
  ├─ 계층별 대시보드 조회 API (공개 / 구매 / 경영기획 / 경영진)
  ├─ ERP·계약 업무 데이터 관리
  ├─ 사용자·스케줄러 분석 오케스트레이션
  └─ FastAPI 분석 호출 및 결과 검증
        │
        ▼
      FastAPI ──────── ChromaDB (계약서 임베딩 저장·검색)
        ├─ XGBoost 트리아지 필터
        ├─ LLM 정보 추출 · Impact Domain 판정
        ├─ Severity 규칙 엔진
        ├─ 계약서 RAG 검색
        └─ LangGraph 멀티에이전트 브리핑
              │
              ▼
            KG service (온톨로지 그래프 리졸버 · 게이트)
```

- **FastAPI는 PostgreSQL에 직접 접근하지 않습니다.** ERP 컨텍스트는 Spring이 조회해 넘겨주고, FastAPI는 ChromaDB(계약 임베딩)만 직접 다룹니다.
- **KG service**는 뉴스 이벤트를 온톨로지 그래프에 매칭해 분석 진행 여부를 결정하는 게이트입니다(`KG_GATE_ENABLED`).

### 멀티에이전트 브리핑 그래프 (LangGraph)

`supervisor`를 중심으로 모든 노드가 supervisor로 복귀합니다.

| 노드 | 역할 |
| --- | --- |
| `supervisor` | 상태를 보고 다음 실행 노드를 결정 |
| `erp` | ERP 노출도 계산, Contract Agent 질문 생성 |
| `contract` | ChromaDB에서 계약 조항 검색 |
| `erp_recheck` | 계약 근거를 반영해 ERP 재검토(최대 1회) |
| `risk` | 외부신호 0.35 + ERP 노출도 0.45 + 계약공백 0.20 |
| `response` | LLM으로 브리핑·권장조치 생성 |
| `reviewer` | 근거 출처·금지표현 검증, 실패 시 담당 노드 재실행(최대 2회) |

---

## 2. 기술 스택

| 영역 | 스택 |
| --- | --- |
| API 중심 | Spring Boot 3.3.2 (Java 17, Gradle), Spring Security(JWT), Spring Data JPA, Flyway |
| AI·ML·RAG | FastAPI (Python 3.12), LangGraph, XGBoost, OpenAI/Anthropic SDK |
| KG | Python 3.12 온톨로지 그래프 서비스 |
| 저장소 | PostgreSQL 16(Spring 전용), ChromaDB 1.5.9(계약 임베딩) |
| 실행 | Docker Compose (5개 서비스) |

---

## 3. 빠른 시작 — Docker

**전제:** Docker Desktop 실행 중, 프로젝트 루트에서 실행.

```bash
# 1) .env 준비 (아래 4장 참고)
cp .env.example .env      # 값 채우기

# 2) 전체 스택 기동 (첫 실행/코드 변경 시 --build)
docker compose up -d --build

# 3) 상태 확인 (모두 healthy 대기)
docker compose ps
```

기동되는 서비스: `postgres` → `chroma` → `kg` → `fastapi` → `spring` (의존 순서대로 healthy 후 다음이 뜸).

```bash
# 헬스 확인
curl http://localhost:8080/actuator/health      # Spring
curl http://localhost:8000/health               # FastAPI
curl http://localhost:8100/health               # KG

# 종료
docker compose down          # 컨테이너만 내림(데이터 유지)
docker compose down -v       # 볼륨까지 삭제(DB·Chroma 초기화 시에만)
```

> ⚠️ `--build`가 `erpseed: failed to resolve source metadata`로 실패하면, Spring 이미지가 `erpseed`·`ragseed` named build context를 요구하는 것입니다 — compose `spring.build`에 `additional_contexts`(`erpseed: ./data/ERP_data/spring-csv`, `ragseed: ./data/RAG_DATA/erp_aligned`)를 선언하거나 `docker build --build-context`로 수동 빌드하세요.

---

## 4. 환경 변수(.env)

`.env.example`을 복사해 채웁니다. `.env`는 gitignore이며 실제 동작을 좌우합니다. **미설정 시 대부분 안전한 기본값**(비용 나가는 스케줄러는 off, 메일 발송 비활성)으로 뜨므로, 키 없이도 부팅은 됩니다.

### 필수/핵심

| 변수 | 설명 |
| --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | DB 계정(기본값 있음) |
| `JWT_SECRET` | JWT 서명 키(운영은 반드시 교체, 32바이트+) |
| `CORS_ALLOWED_ORIGINS` | 허용 오리진(기본 `http://localhost:5173,3000,4173`) |
| `OPENAI_API_KEY` | 임베딩·추출·번역 공용 |
| `ANTHROPIC_API_KEY` | 브리핑 생성 LLM(claude, `BRIEFING_USE_CLAUDE=true`일 때) |
| `EMBEDDING_PROVIDER` | 기본 `openai`. 키 없이 로컬이면 **명시적으로 `mock`** 지정 |

### 데이터 적재 플래그

| 변수 | 기본 | 설명 |
| --- | --- | --- |
| `ERP_SEED_ENABLED` | false | ERP CSV → PostgreSQL 자동 적재 |
| `RAG_SEED_ENABLED` | false | 계약서 → ChromaDB 자동 임베딩(ERP 시드 필요) |
| `AUTH_TEST_SEED_ENABLED` | false | 프론트 e2e용 고정 테스트 계정 시드 |

### 비용이 나가는 스케줄러 (기본 off — 켤 때만 LLM 비용 발생)

| 변수 | 설명 |
| --- | --- |
| `COLLECTION_SCHEDULER_ENABLED` | GDELT 뉴스 수집(15분). 수집 자체는 무료 |
| `COLLECTION_ANALYSIS_ENABLED` | 수집 뉴스 F3 분석(LLM 추출 호출) |
| `COLLECTION_TRANSLATION_ENABLED` | 제목 한국어 번역(LLM) |
| `RISK_SCORING_ENABLED` | 구매 리스크 점수 자동 축적 |

> 무료 공개 API 기반인 `EXCHANGE_RATE_SCHEDULER_ENABLED`·`MARKET_PRICE_SCHEDULER_ENABLED`는 기본 on(키 없으면 조용히 스킵).

### 메일 알림(F10, 선택)

`SMTP_HOST`/`SMTP_PORT`/`SMTP_USERNAME`/`SMTP_PASSWORD`/`NOTIFICATION_MAIL_FROM`. 전부 비우면 발송만 비활성되고 부팅은 정상. Gmail이면 앱 비밀번호 사용.

---

## 5. 서비스·포트

| 서비스 | 컨테이너 | 호스트 포트 | 역할 |
| --- | --- | --- | --- |
| spring | `battery-risk-spring` | **8080** | API 중심(프론트가 호출) |
| fastapi | `battery-risk-fastapi` | 8000 | AI·ML·RAG(내부) |
| kg | `battery-risk-kg` | 8100 | 온톨로지 그래프 리졸버 |
| chroma | `battery-risk-chroma` | 8001 → 8000 | 계약 임베딩 벡터 저장소 |
| postgres | `battery-risk-postgres` | 5432 | 업무·이벤트·AI 결과 |

- Spring Swagger: `http://localhost:8080/swagger-ui.html`
- FastAPI Swagger: `http://localhost:8000/docs`

---

## 6. 디렉터리 구조

```
backend_merge/
├─ spring-backend/        # Spring Boot (Java 17, Gradle)
│  └─ src/main/resources/db/migration/   # Flyway V1~V36
├─ fastapi-ai/            # FastAPI (Python 3.12, LangGraph 멀티에이전트)
├─ kg_service/            # KG 온톨로지 그래프 서비스
├─ data/                  # ERP CSV · RAG 계약서 원문(시드 소스)
│  ├─ ERP_data/spring-csv/
│  └─ RAG_DATA/erp_aligned/
├─ data_core/ · data_ref/ # 참조 데이터
├─ docs/                  # 설계·인터페이스·핸드오프 문서
├─ scripts/               # 재임베딩 등 유틸 스크립트
└─ docker-compose.yml
```

> 코딩 규칙: Spring은 관련 폴더마다 기능 파일 최대 1개, FastAPI는 API·Schema·Service 각각 기능 파일 1개.

---

## 7. 데이터 적재(Seed)

`docker compose up` 시 플래그가 켜져 있으면 자동 적재됩니다(수동 스크립트 불필요).

| 데이터 | 적재기 | 저장소 | 활성 조건 |
| --- | --- | --- | --- |
| ERP(자재·공급사·계약·재고·발주) | `ErpSeedConfig` | PostgreSQL | `ERP_SEED_ENABLED=true` |
| RAG 계약서 임베딩 | `RagSeedConfig` | ChromaDB | `RAG_SEED_ENABLED=true` + `EMBEDDING_PROVIDER=openai` |
| 테스트 계정 | `AuthTestSeedConfig` | PostgreSQL | `AUTH_TEST_SEED_ENABLED=true` |

RAG 시드는 `data/RAG_DATA/erp_aligned/`의 계약서를 파일명으로 PostgreSQL PK에 매핑해 OpenAI로 임베딩합니다. 재실행 시 dedup으로 중복이 없습니다.

### 테스트 계정 (`AUTH_TEST_SEED_ENABLED=true`)

| 아이디(username) | 비밀번호 | 계층 |
| --- | --- | --- |
| `purchasing@test.local` | `test1234!` | 1계층(구매팀) |
| `planning@test.local` | `test1234!` | 2계층(경영기획팀) |
| `executive@test.local` | `test1234!` | 3계층(경영진) |
| `admin@test.local` | `test1234!` | 관리자(가입 관리) |
| `pending@company.com` | `anything` | 승인 대기 |

> 로그인 아이디(`username`)와 알림 수신 이메일(`email`)은 별개 컬럼입니다. 시드는 `email`을 실제 받은편지함으로 덮을 수 있어 둘이 다를 수 있습니다.

---

## 8. 로컬 개발(Docker 없이)

PostgreSQL·Chroma·KG는 Docker로 띄우고, Spring/FastAPI만 로컬에서 돌리는 조합이 흔합니다.

### Spring Boot

```bash
cd spring-backend
./gradlew bootRun          # Windows: .\gradlew.bat bootRun
```

DB에 붙으려면 `POSTGRES_URL=jdbc:postgresql://localhost:5432/battery_risk` 등 환경변수를 넘깁니다. Flyway가 기동 시 V1~V36을 적용합니다.

### FastAPI

```bash
cd fastapi-ai
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000 --env-file ../.env
```

---

## 9. API 개요

모든 엔드포인트는 `/api/v1` 하위. 프론트는 Spring만 호출합니다. Swagger의 `Authorize`에 로그인 응답의 `access_token`을 넣어 보호 API를 테스트할 수 있습니다.

| 그룹 | Base Path | 비고 |
| --- | --- | --- |
| 인증 | `/auth` | signup·login·refresh·logout·me, 관리자 가입 승인 |
| 공개(비로그인) | `/public`, `/map`, `/risk-monitoring`, `/material-risk` | 계층 무관 대시보드 |
| 1계층(구매) | `/ai-briefing`, `/analyses`, `/suppliers`, `/erp`, `/contract-rag`, `/rag`, `/documents` | 실무 관제·브리핑 |
| 2계층(경영기획) | `/planning` | 전략 대시보드·AI 브리핑 취합 |
| 3계층(경영진) | `/executive` | 누적 KPI·시뮬레이션 |
| 멀티에이전트 | `/multi-agent` | 브리핑 생성·리스크 스코어링 |
| Severity | `/severity/assessments` | 규칙 엔진 평가 |
| 운영/수집 | `/collection`, `/erp/admin`, `/erp/imports`, `/market-price/admin`, `/notifications` | 스케줄러·관리자 |

### 인증 개요

- 공개: `/auth/signup`, `/login`, `/refresh`, `/public/**`
- 보호: `/auth/logout`, `/auth/me`, 그 외 `/api/v1/**`
- 회원가입 역할: `PURCHASING`(구매) · `STRATEGY`(경영기획) · `EXECUTIVE`(경영진), 승인 관리 역할 `ADMIN`
- JWT는 `JWT_SECRET`으로 서명. 로그아웃 세션은 PostgreSQL에 저장되어 재시작 후에도 차단 유지, 만료 세션은 스케줄러가 정리.

---

## 10. 데이터 파이프라인

뉴스 → 분석 → 브리핑까지 단계별 산출물이 각기 다른 테이블에 쌓입니다.

| 단계 | 산출물 테이블 | 내용 |
| --- | --- | --- |
| 수집 | `raw_events` | GDELT 15분 구간 + XGBoost 트리아지로 선별·크롤링 |
| 분석 | `analyses` | 트리아지 통과 건에 LLM 추출 + Severity 판정까지 |
| 멀티에이전트 | `procurement_risk_assessments` | ERP·계약 근거 반영한 종합 위험 점수 |
| 브리핑 | `ai_briefings` | 최종 브리핑 텍스트·권장조치(멀티에이전트 완결 결과물) |

- `analyses`는 브리핑 **전 단계**(트리아지+LLM+severity)이고, `ai_briefings`가 멀티에이전트 완결 브리핑입니다. 계층별 "AI 브리핑" 화면은 `ai_briefings`를 기준으로 집계합니다.
- 자동 수집·분석은 비용 방지를 위해 기본 off이며, 수동 트리거(`POST /collection/run` 등)는 플래그와 무관하게 동작합니다.

### ML 모델 현황

- 학습된 XGBoost는 **트리아지 필터** 하나(`fastapi-ai/app/models/triage_filter.json`, threshold 0.38). 모델 확률 ≥ 임계값 **또는** 핵심 생산국 7개국 화이트리스트면 통과.
- Impact Domain은 LLM 추출 결과를 그대로 사용(분류 확률 없음, confidence None).

---

## 11. DB 마이그레이션

- Flyway가 Spring 기동 시 `spring-backend/src/main/resources/db/migration/`의 **V1~V36**을 순서대로 적용합니다.
- **적용된 마이그레이션은 수정하지 않고 새 버전을 추가**합니다(체크섬 검증). 규칙은 해당 폴더의 `README.md` 참고.

---

## 12. 테스트

```bash
# Spring
cd spring-backend
./gradlew test              # Windows: .\gradlew.bat test

# FastAPI
cd fastapi-ai
python -m pytest
```

---

관련 문서는 `docs/`, 변경 이력은 [CHANGELOG.md](CHANGELOG.md)를 참고하세요.
