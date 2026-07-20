# 배터리 원자재 공급망 리스크 관제 시스템 — Interface Specification v0.1

## 서비스 경계

- React는 Spring Boot 공개 API만 호출한다.
- Spring Boot는 화면 조회와 ERP·계약 데이터를 담당한다.
- FastAPI는 외부 데이터 처리, AI·ML·RAG와 브리핑 생성을 담당한다.
- 서비스 간 실제 통신 모델만 1:1로 맞추며, 화면 DTO와 FastAPI 내부 모델은 독립적으로 관리한다.

## 공통 규칙

- JSON 필드: `camelCase`
- 날짜·시간: ISO 8601(시간대 포함 권장)
- 성공: `{"success":true,"data":{},"timestamp":"..."}`
- 실패: `{"success":false,"error":{"code":"...","message":"...","details":null},"timestamp":"..."}`
- HTTP: 성공 `200/201/202`, 요청 오류 `400/422`, 미존재 `404`, 충돌 `409`, 서버 오류 `500/503`

### Enum

- Severity: `NORMAL`, `WARNING`, `CRITICAL`
- ImpactDomain: `PRODUCTION`, `LOGISTICS`, `POLICY`, `MARKET`, `GEOPOLITICS`, `IRRELEVANT`
- EvidenceType: `CONFIRMED`, `REFERENCE`, `WARNING`
- ProcessingStatus: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`

## Spring Boot 공개 API

- `GET /api/v1/dashboard/summary`
- `GET /api/v1/risks`
- `GET /api/v1/risks/{riskId}`
- `GET /api/v1/contracts`
- `GET /api/v1/contracts/{contractId}`
- `GET /api/v1/risks/{riskId}/briefing`

리스크 상세 응답은 `source`, `material`, `supplier`, `analysis`, `inventory` 객체를 포함한다. 목록 API는 `content`, `page`, `size`, `totalElements`, `totalPages` 페이지 구조를 사용한다.

## FastAPI 통합 API

- `POST /api/v1/analyze`
- `POST /api/v1/rag/contracts` (`multipart/form-data`)
- `POST /api/v1/rag/search`

`/analyze` Mock은 실제 응답 스키마를 유지하고 `mock`, `mockReason`을 명시한다. RAG 검색은 `contractId` 또는 `supplierId` 중 하나를 필수로 받고 `topK` 범위는 1–20이다.

## FastAPI 내부 개발 API

- `POST /api/v1/internal/llm/extract`
- `POST /api/v1/internal/ml/classify`
- `POST /api/v1/internal/severity/score`
- `POST /api/v1/internal/briefings`

내부 API는 개발·Swagger 테스트용이며 React에서 직접 호출하지 않는다.

## 데이터 소유권

| 데이터 | 쓰기 책임 | 읽기 |
|---|---|---|
| Material, Supplier, Inventory, Purchase Order, Contract | Spring Boot | Spring Boot, FastAPI |
| Risk Event, Feature, Classification, Severity | FastAPI | Spring Boot, FastAPI |
| Briefing | FastAPI | Spring Boot |
| Vector Document | FastAPI | FastAPI |

## 문서 주소

- Spring: `http://localhost:8080/swagger-ui.html`
- FastAPI: `http://localhost:8000/docs`

변경 사항은 루트 `CHANGELOG.md`에 기록한다.
