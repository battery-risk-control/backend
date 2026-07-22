# Battery Risk MVP Starter

Interface Specification v0.2를 기준으로 만든 초기 구현입니다.

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
uvicorn app.main:app --reload --port 8000
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

Spring Boot의 문서 Metadata는 PostgreSQL에, 원본 파일은 `uploads/contracts/{document_id}`에 영구 저장됩니다. FastAPI의 검색용 문서 저장은 아직 In-memory Mock이며 추후 ChromaDB로 교체합니다.

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

이 배열이 5단계의 Embedding·ChromaDB 입력입니다. 5단계에서는 파일을 다시 읽거나 다시 청킹하지 않습니다. 현재 단계에는 Embedding과 ChromaDB 저장은 포함되지 않습니다.

문서 처리 오류 코드는 `EMPTY_DOCUMENT`, `UNSUPPORTED_DOCUMENT_TYPE`, `INVALID_PDF`, `TEXT_EXTRACTION_FAILED`, `CHUNKING_FAILED`로 구분합니다.

---

# C2 로그인·인증·권한 실행

Spring Swagger의 `Authorize` 버튼에 로그인 응답의 `access_token`을 입력해 보호 API를 테스트할 수 있습니다.

- 공개: `POST /api/v1/auth/signup`, `/login`, `/refresh`, `/api/v1/dashboard/**`
- 인증 필요: `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, 그 외 `/api/v1/**`
- 회원가입 역할: `PURCHASING`, `STRATEGY`, `EXECUTIVE`
- JSON 토큰 필드: `access_token`, `refresh_token`, `token_type`, `expires_in`, `refresh_expires_in`

JWT 비밀키는 실행 환경의 `JWT_SECRET`으로 설정합니다. 로그아웃된 JWT 세션은 PostgreSQL `revoked_token_sessions`에 저장되므로 Spring 재시작 후에도 차단 상태가 유지됩니다. 만료된 세션 행은 Scheduler가 주기적으로 정리합니다.

---
