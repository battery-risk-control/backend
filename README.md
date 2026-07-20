# Battery Risk MVP Starter

Interface Specification v0.1을 기준으로 만든 초기 구현입니다.

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
- `GET /api/v1/risks/{riskId}`
- `GET /api/v1/contracts/{contractId}`
- `GET /api/v1/risks/{riskId}/briefing`
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

인터페이스 기준은 [docs/interface-spec-v0.1.md](docs/interface-spec-v0.1.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md)를 확인합니다.

Spring CORS는 기본적으로 React 개발 주소 `http://localhost:3000`과 `http://localhost:5173`을 허용합니다. 배포 환경에서는 `CORS_ALLOWED_ORIGINS` 환경 변수로 변경합니다.
