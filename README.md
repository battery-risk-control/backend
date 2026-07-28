# Battery Risk MVP Starter

Interface Specification v0.2를 기준으로 만든 초기 구현입니다.

## 빠른 시작 — Docker 한 번에 실행 (ERP + RAG 자동 적재)

**`docker compose up` 한 번으로 전체 스택 기동 + 데이터 적재까지 자동으로 됩니다.**
플래그만 켜두면 시작 시 ① ERP 데이터가 PostgreSQL에, ② 계약서 RAG 임베딩이 ChromaDB에 자동 적재됩니다. (수동 적재 스크립트 실행 불필요)

### 무엇이 자동으로 적재되나

| 데이터 | 적재기 | 저장소 | 활성 조건 |
| --- | --- | --- | --- |
| **ERP** (자재·공급사·계약·재고·발주) | `ErpSeedConfig` | PostgreSQL | `ERP_SEED_ENABLED=true` |
| **RAG 계약서 임베딩** (erp_aligned 28건) | `RagSeedConfig` | ChromaDB | `RAG_SEED_ENABLED=true` + `EMBEDDING_PROVIDER=openai` |

> ⚠️ RAG 적재는 계약 매핑(ERP)이 필요하므로 **ERP 시드가 함께 켜져 있어야** 합니다. RAG 시드는 `erp_aligned/CTR-XXX_*.txt`를 파일명으로 PostgreSQL PK에 매핑해 OpenAI로 임베딩합니다.

### 1) `.env` 설정

`.env.example`을 `.env`로 복사한 뒤 아래를 채웁니다. (`.env`는 gitignore — 각자 로컬에서 만들며, 실제 동작은 이 `.env`가 좌우합니다. `.env.example`은 템플릿이라 앱이 읽지 않습니다.)

```bash
# --- OpenAI (임베딩 + 채팅 LLM 공용 키) ---
EMBEDDING_PROVIDER=openai                        # mock 아니라 openai (없으면 조용히 mock으로 동작)
OPENAI_API_KEY=sk-...                            # 임베딩·채팅 공용
OPENAI_EMBEDDING_MODEL=text-embedding-3-large    # 임베딩 모델
EMBEDDING_DIMENSION=3072                         # 3-large 기본 차원 (모델과 일치 필수)
OPENAI_MODEL=gpt-4o-mini                         # 채팅(브리핑 생성) LLM

# --- 자동 적재 플래그 ---
ERP_SEED_ENABLED=true                            # ERP → PostgreSQL 자동 적재
RAG_SEED_ENABLED=true                            # 계약서 RAG → ChromaDB 자동 임베딩
```

> ⚠️ `EMBEDDING_PROVIDER=openai`가 없으면 키가 있어도 **mock 임베딩**(가짜)으로 돕니다. 시드 플래그가 없으면 해당 데이터가 **빈 상태**로 뜹니다.

### 2) 포트/이름 충돌 확인

다른 백엔드 스택(mvp-starter 등)과 **컨테이너 이름·호스트 포트(5432·8001·8080·5173)가 동일**합니다. 다른 스택이 떠 있으면 먼저 내립니다.

```powershell
docker compose -f C:\aivleschool\bigproject\battery-risk-mvp-starter\docker-compose.yml down
```

### 3) 실행

`.env`가 확실히 로딩되도록 **반드시 이 폴더 안에서** 실행합니다. (다른 폴더에서 `-f`로 지정하면 `.env`가 안 읽혀 mock으로 떨어질 수 있습니다.)

```powershell
cd C:\aivleschool\bigproject\backend_merge
docker compose up -d --build     # 첫 실행/코드 변경 시 --build 필수 (Seed 코드 반영)
docker compose ps                # healthy 대기
```

기동 시 자동으로:
- **ErpSeedConfig** → ERP CSV를 PostgreSQL에 적재 (자재 11·공급사 19·계약 29 등)
- **RagSeedConfig** → FastAPI가 준비되면 계약서 28건을 OpenAI 임베딩해 ChromaDB에 적재 (재실행 시 dedup으로 중복 없음)

### 4) 적재 확인

```powershell
# ERP·RAG 시드 로그
docker compose logs spring | Select-String "ERP CSV seed|RAG seed"
# RAG 임베딩(chunk_count > 0, mock=false) 확인
docker exec battery-risk-fastapi python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/health').read().decode())"
```
`RAG seed 완료: 성공 28 ...` / `chunk_count`가 0보다 크고 `embedding_type=OPENAI`면 RAG 임베딩 성공입니다.

### 5) 종료

```powershell
docker compose down        # 컨테이너만 내림 (데이터 유지)
docker compose down -v     # 볼륨까지 삭제 (DB·Chroma 초기화가 필요할 때만)
```

접속 주소: Spring `http://localhost:8080` · FastAPI(내부) `fastapi:8000` · Chroma `http://localhost:8001` · Frontend `http://localhost:5173`

> **참고 — 수동 재임베딩**은 이미 적재된 문서를 다른 모델/provider로 **다시** 임베딩할 때만 필요합니다: `python scripts/reindex_embeddings.py`. 처음 적재는 위 RagSeedConfig가 자동 처리하므로 별도 실행이 필요 없습니다.

## 1. Spring Boot 실행

```bash
cd spring-backend
./gradlew bootRun
```

Windows에서는 `gradlew.bat bootRun`을 실행합니다.

Swagger:

- http://localhost:8080/swagger-ui.html

## 2. FastAPI 실행

```bash
cd fastapi-ai
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

macOS/Linux:

```bash
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Swagger:

- http://localhost:8000/docs
- 검증 화면: [docs/fastapi-swagger.png](docs/fastapi-swagger.png)

## 3. 현재 구현 범위

### Spring Boot

- `GET /api/v1/dashboard/summary`
- `GET /api/v1/risks`
- `GET /api/v1/risks/{risk_id}`
- `GET /api/v1/contracts/{contract_id}`
- `GET /api/v1/risks/{risk_id}/briefing`
- `GET /api/v1/contracts`

### FastAPI

- `POST /api/v1/analyze`
- `POST /api/v1/rag/contracts`
- `POST /api/v1/rag/search`
- `GET /health`
- `POST /api/v1/internal/llm/extract`
- `POST /api/v1/internal/ml/classify`
- `POST /api/v1/internal/severity/score`
- `POST /api/v1/internal/briefings`

현재 응답은 실제 Interface Specification과 동일한 구조를 사용하는 Mock입니다.

FastAPI는 실제 연동 전에도 교체 지점이 분리되어 있습니다.

- `core/config.py`: 실행 환경 및 Mock mode
- `services/`: 추출, 분류, Severity, ERP Context, RAG 오케스트레이션
- `ml/inference.py`: 향후 XGBoost adapter 교체 지점
- `rag/`: loader, chunker, vector store 경계
- `repositories/`: 향후 PostgreSQL repository 교체 지점

현재 기본값은 `MOCK_MODE=true`이며 ERP repository와 vector store는 in-memory 구현을 사용합니다.
실제 연동 전에도 입력 기반 Extraction·분류·Severity, RAG 업로드→검색, 모든 option 분기와 공통 오류 응답을 테스트할 수 있습니다.

## 4. 테스트

```bash
cd spring-backend
./gradlew test

cd ../fastapi-ai
python -m pytest
```

인터페이스 기준은 [docs/interface-spec-v0.2.md](docs/interface-spec-v0.2.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md)를 확인합니다.

Spring CORS는 기본적으로 React 개발 주소 `http://localhost:3000`과 `http://localhost:5173`을 허용합니다. 배포 환경에서는 `CORS_ALLOWED_ORIGINS` 환경 변수로 변경합니다.
# PostgreSQL 기본 환경

PostgreSQL은 Spring Boot만 읽고 쓰며, FastAPI에는 PostgreSQL 계정·Driver·ORM을 추가하지 않습니다.

## 1. 환경 변수 준비

프로젝트 루트의 `.env.example`을 참고하여 필요하면 `.env`를 만듭니다. 기본 로컬 값은 별도 `.env` 없이도 동작합니다.

## 2. PostgreSQL 실행

Docker Desktop을 먼저 실행한 다음 프로젝트 루트에서 실행합니다.

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter
docker compose up -d postgres
docker compose ps
```

`battery-risk-postgres`가 `healthy`가 되면 준비된 상태입니다.

## 3. Spring Boot·Flyway 실행

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter\spring-backend
.\gradlew.bat bootRun
```

Spring Boot 시작 시 Flyway가 V1 Master/문서 Schema와 V2 인증 Schema를 순서대로 적용합니다.

- PostgreSQL: `localhost:5432/battery_risk`
- Spring Health: `http://localhost:8080/actuator/health`
- Spring Swagger: `http://localhost:8080/swagger-ui.html`

## 4. 종료와 데이터 유지

```powershell
docker compose stop postgres
docker compose start postgres
```

Named Volume `battery_postgres_data`를 사용하므로 컨테이너를 정지·재시작해도 데이터가 유지됩니다. `docker compose down -v`는 Volume까지 삭제하므로 데이터 초기화가 명확히 필요할 때만 사용합니다.

## Migration 책임

- `V1`: F6 최소 Master Data와 C1 문서 Schema
- `V2`: C2 사용자·인증·권한 Schema
- 적용된 Migration은 수정하지 않고 새 버전을 추가
- 전체 규칙: `spring-backend/src/main/resources/db/migration/README.md`

---

# C1 문서 업로드 실행

C1 업로드는 React가 Spring Boot만 호출하고, Spring Boot가 FastAPI 문서 처리 API를 호출하는 구조입니다.

## 1. FastAPI 실행

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter\fastapi-ai
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000 --env-file ../.env
```

- Swagger: `http://localhost:8000/docs`
- 내부 문서 처리 API: `POST /api/v1/documents/process`

## 2. Spring Boot 실행

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter\spring-backend
.\gradlew.bat bootRun
```

- Swagger: `http://localhost:8080/swagger-ui.html`
- 프론트엔드 업로드 API: `POST /api/v1/documents`

## 업로드 요청

`multipart/form-data` 필드:

| 필드 | 형식 | 필수 | 설명 |
| --- | --- | --- | --- |
| `file` | PDF/TXT | 예 | 최대 10MB |
| `contract_id` | 양의 정수 | 예 | 계약 ID |
| `supplier_id` | 양의 정수 | 예 | 공급사 ID |
| `material_id` | 양의 정수 | 예 | 자재 ID |
| `document_type` | 문자열 | 아니요 | 기본값 `LTA` |

Spring Boot의 문서 Metadata는 PostgreSQL에, 원본 파일은 `uploads/contracts/{document_id}`에 영구 저장됩니다. FastAPI는 추출한 청크를 Mock Embedding으로 변환하여 ChromaDB에 저장합니다.

## 4단계 문서 추출·청킹 출력 계약

FastAPI 내부 API `POST /api/v1/documents/process`는 Spring Boot가 발급한 `document_id`를 그대로 사용합니다. PDF는 실제 페이지 번호를 보존하고, 계약 조항을 먼저 나눈 뒤 문단 단위로 묶으며, 한 문단이 너무 긴 경우에만 길이와 overlap을 적용합니다.

`data.chunks[]`는 다음 `snake_case` 필드를 반환합니다.

| 필드 | 설명 |
| --- | --- |
| `document_id` | Spring Boot에서 발급한 문서 ID |
| `chunk_index` | 문서 전체에서 0부터 시작하는 청크 순서 |
| `page_number` | 원본 PDF의 1부터 시작하는 페이지 번호; TXT는 1 |
| `content` | 추출·청킹된 텍스트 |
| `contract_id` | 계약 ID |
| `supplier_id` | 공급사 ID |
| `material_id` | 자재 ID |
| `document_type` | 문서 유형 |
| `content_hash` | 원본 파일의 SHA-256 |

이 배열이 Embedding·ChromaDB 입력입니다. 이후 단계에서는 파일을 다시 읽거나 다시 청킹하지 않습니다. `content`만 Embedding 본문으로 사용하고 나머지 필드는 Chroma Metadata로 저장합니다.

문서 처리 오류 코드는 `EMPTY_DOCUMENT`, `UNSUPPORTED_DOCUMENT_TYPE`, `INVALID_PDF`, `TEXT_EXTRACTION_FAILED`, `CHUNKING_FAILED`로 구분합니다.

## 6단계 Mock Embedding

기본 설정은 API Key가 필요 없는 결정론적 Mock Provider입니다.

```dotenv
EMBEDDING_PROVIDER=mock
EMBEDDING_DIMENSION=1536
```

`MockEmbedding`은 Chroma/LangChain에서 사용하는 다음 두 메서드 계약을 제공합니다.

```python
provider.embed_documents(["첫 번째 청크", "두 번째 청크"])
provider.embed_query("리튬 가격 조정")
```

- 동일한 텍스트는 실행과 재시작에 관계없이 동일한 벡터를 생성합니다.
- 기본 벡터 차원은 `text-embedding-3-small` 기본값과 같은 1,536입니다.
- `EMBEDDING_PROVIDER=openai`일 때는 실제 Provider 객체를 외부에서 주입해야 합니다.
- Mock과 OpenAI 벡터는 같은 Chroma Collection에 혼합하지 않습니다.
- 실제 OpenAI API Key는 코드에 작성하지 않고 환경변수로만 전달합니다.

## 5~6단계 ChromaDB·Mock Embedding 연결

Docker ChromaDB는 FastAPI의 `8000` 포트와 겹치지 않도록 호스트 `8001` 포트를 사용합니다.

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter
docker compose up -d chroma
```

FastAPI를 Docker ChromaDB에 연결할 때는 다음 환경변수를 지정합니다.

```dotenv
CHROMA_MODE=http
CHROMA_HOST=localhost
CHROMA_PORT=8001
CHROMA_SSL=false
CHROMA_COLLECTION_PREFIX=contract_documents
```

별도 Chroma 컨테이너 없이 로컬 개발을 할 때는 `CHROMA_MODE=persistent`와 `CHROMA_PERSIST_DIRECTORY=./data/chroma`를 사용합니다. 테스트는 운영 데이터를 지우지 않도록 `ephemeral` 모드에서 격리됩니다.

- Mock Collection: `contract_documents_mock_v1`
- 청크 ID: `{document_id}:{chunk_index}`
- 재적재: 같은 `document_id`의 기존 청크를 지운 뒤 새 청크 저장
- 검색: `contract_id` 또는 `supplier_id` 필수 필터 후 Vector 검색, `material_id` 선택 필터
- 검색 점수: cosine distance를 `0.0~1.0` 유사도로 변환
- Health Check: `GET http://localhost:8000/health`
- 저장 Metadata: 원본 4단계 Metadata와 `embedding_type`, `embedding_version`, `mock_embedding`

Mock 적재 성공 시 Spring Boot의 `contract_documents`에는 `embedding_type=MOCK_TOKEN_HASH`, `embedding_version=mock-v1`이 저장됩니다. 실제 Embedding은 아직 연결하지 않았습니다.

## 7단계 C1-B 실제 업로드·검색 E2E

React와 Swagger는 인증 후 Spring Boot만 호출합니다.

```http
POST /api/v1/documents
GET  /api/v1/documents/{document_id}
POST /api/v1/documents/{document_id}/reprocess
POST /api/v1/rag/search
```

검색 요청 예시:

```json
{
  "query": "리튬 가격 조정 조건",
  "filters": {
    "contract_id": 1,
    "supplier_id": 1,
    "material_id": 1
  },
  "top_k": 5
}
```

`contract_id` 또는 `supplier_id` 중 하나는 필수입니다. Spring의 검색 서비스는 FastAPI `/api/v1/rag/search`를 호출하며 검색 장애가 발생해도 이미 완료된 PostgreSQL 문서 상태는 변경하지 않습니다.

문서 재처리는 저장된 원본 파일과 같은 `document_id`를 사용하고 FastAPI에 `force_reprocess=true`를 전달합니다. FastAPI는 기존 Chroma 청크를 삭제한 뒤 다시 적재하므로 오래된 청크가 남지 않습니다.

오류 상태:

| 상황 | PostgreSQL `processing_status` | `error_code` |
| --- | --- | --- |
| FastAPI 연결 실패 | `FAILED` | `FASTAPI_UNAVAILABLE` |
| 손상 PDF | `FAILED` | `INVALID_PDF` |
| TXT 디코딩 실패 | `FAILED` | `TEXT_EXTRACTION_FAILED` |
| 청킹 실패 | `FAILED` | `CHUNKING_FAILED` |
| Chroma 연결 실패 | `FAILED` | `VECTOR_STORE_UNAVAILABLE` |
| Chroma 적재 실패 | `FAILED` | `VECTOR_STORE_FAILED` |

실제 네 서비스 E2E에서 정상 TXT·2페이지 PDF 업로드, 중복·재처리, Metadata Filter, 장애 상태 저장, PostgreSQL·Chroma 재시작 후 영속성을 확인했습니다. 상세 결과는 `docs/c1-b-e2e-implementation.md`를 참고합니다.

---

# C2 로그인·인증·권한 실행

Spring Swagger의 `Authorize` 버튼에 로그인 응답의 `access_token`을 입력해 보호 API를 테스트할 수 있습니다.

- 공개: `POST /api/v1/auth/signup`, `/login`, `/refresh`, `/api/v1/dashboard/**`
- 인증 필요: `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, 그 외 `/api/v1/**`
- 회원가입 역할: `PURCHASING`, `STRATEGY`, `EXECUTIVE`
- JSON 토큰 필드: `access_token`, `refresh_token`, `token_type`, `expires_in`, `refresh_expires_in`

JWT 비밀키는 실행 환경의 `JWT_SECRET`으로 설정합니다. 로그아웃된 JWT 세션은 PostgreSQL `revoked_token_sessions`에 저장되므로 Spring 재시작 후에도 차단 상태가 유지됩니다. 만료된 세션 행은 Scheduler가 주기적으로 정리합니다.

---

# F6 ERP Seed와 F1 ERP Context 계산

F6는 외부 ERP의 문자열 ID를 보존하면서 Spring/PostgreSQL 내부 PK와 분리합니다. 예를 들어 `MAT-LI-CARB`는 `materials.erp_material_id`에 저장되고, 관계 테이블은 내부 `material_id` BIGINT를 FK로 사용합니다.

CSV 적재는 명시적으로 활성화할 때만 실행됩니다.

```powershell
$env:ERP_SEED_ENABLED='true'
$env:ERP_SEED_DIRECTORY='C:\aivleschool\bigproject\spring-csv'
cd C:\aivleschool\bigproject\battery-risk-mvp-starter\spring-backend
.\gradlew.bat bootRun
```

`00_manifest.csv`의 순서와 행 수를 검증한 뒤 10개 CSV를 하나의 트랜잭션으로 upsert합니다. 같은 CSV를 다시 적재해도 외부 ERP ID를 기준으로 갱신되며 중복 행을 만들지 않습니다.

인증 후 아래 Spring API로 F1의 결정적 ERP 수치를 계산할 수 있습니다.

```http
POST /api/v1/erp/context
Authorization: Bearer {access_token}
Content-Type: application/json
```

```json
{
  "erp_material_id": "MAT-LI-CARB",
  "erp_supplier_id": "SUP-CHL-01",
  "as_of": "2026-07-22T12:00:00+09:00"
}
```

응답에는 가용재고, 평균 일사용량, 재고일수, 안전재고일수·부족량, 다음 입고일·남은 일수, 예상 공급 공백, 미입고 수량, 공급사 의존도가 포함됩니다. 이 단계의 데이터 출처는 `ERP_MOCK`이며 실제 ERP 연결 또는 FastAPI의 설명 생성 기능은 아직 포함하지 않습니다.

---

# 11단계 Severity Rule Engine

Spring은 F1 ERP Context와 외부 위험 신호를 조합하여 FastAPI의 결정적 규칙 엔진을 호출하고, 입력 Snapshot과 결과를 PostgreSQL `severity_assessments`에 저장합니다. React는 FastAPI를 직접 호출하지 않습니다.

```http
POST /api/v1/severity/assessments
GET  /api/v1/severity/assessments/{assessment_id}
```

Spring 요청 예시:

```json
{
  "erp_material_id": "MAT-LI-CARB",
  "erp_supplier_id": "SUP-CHL-01",
  "as_of": "2026-07-22T12:00:00+09:00",
  "price_change_rate": 11.5,
  "logistics_delay_days": 7,
  "gdacs_alert_level": 2
}
```

내부 FastAPI API는 다음 9개 입력을 받습니다.

```http
POST /api/v1/internal/severity/score
```

```text
inventory_days, safety_stock_days, expected_supply_gap_days,
supplier_dependency_ratio, price_change_rate, logistics_delay_days,
gdacs_alert_level, feoc_status, data_quality_status
```

규칙 버전 `severity-rule-v1`의 등급 기준은 `NORMAL < 30`, `WARNING 30~69.9`, `CRITICAL >= 70`입니다. 사용 가능한 수치가 전혀 없거나 데이터 품질이 `INVALID`이면 `UNKNOWN`을 반환합니다. `feoc_status=YES`는 Hard Gate이므로 100점 `CRITICAL`로 강제 격상하고 `FEOC_HARD_GATE` 근거 코드를 남깁니다.

누락된 외부 신호는 임의 생성하지 않고 `null`로 전달합니다. 응답의 `calculation_details`에는 항목별 점수, 사용한 입력, 누락 입력, 등급 임계치와 강제 격상 여부가 포함됩니다.

---
