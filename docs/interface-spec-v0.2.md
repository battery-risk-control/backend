# 배터리 원자재 공급망 리스크 관제 시스템 — Interface Specification v0.2

## 서비스 경계

- React는 Spring Boot 공개 API만 호출한다.
- Spring Boot는 화면 조회와 ERP·계약 데이터를 담당한다.
- FastAPI는 외부 데이터 처리, AI·ML·RAG와 브리핑 생성을 담당한다.
- 서비스 간 실제 통신 모델만 1:1로 맞추며, 화면 DTO와 FastAPI 내부 모델은 독립적으로 관리한다.

## 공통 규칙

- JSON 필드: `snake_case`
- Java와 Python 내부 변수명은 언어 관례를 따를 수 있지만 직렬화된 JSON과 Multipart 필드명은 `snake_case`를 사용한다.
- 날짜·시간: ISO 8601(시간대 포함 권장)
- 성공: `{"success":true,"data":{},"timestamp":"..."}`
- 실패: `{"success":false,"error":{"code":"...","message":"...","details":null},"timestamp":"..."}`
- HTTP: 성공 `200/201/202`, 요청 오류 `400/422`, 미존재 `404`, 충돌 `409`, 서버 오류 `500/503`

### Enum

- Severity: `NORMAL`, `WARNING`, `CRITICAL`
- ImpactDomain: `PRODUCTION`, `LOGISTICS`, `POLICY`, `MARKET`, `GEOPOLITICS`, `IRRELEVANT`
- EvidenceType: `CONFIRMED`, `REFERENCE`, `WARNING`
- ProcessingStatus: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`
- Role: `PURCHASING`, `STRATEGY`, `EXECUTIVE`

## Spring Boot 공개 API

- `POST /api/v1/auth/signup`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `POST /api/v1/documents`
- `GET /api/v1/documents/{document_id}`
- `GET /api/v1/dashboard/summary`
- `GET /api/v1/map/realtime-alerts`
- `GET /api/v1/risk-events` — 리스크 이벤트 목록 (프론트 `RiskEvent` 계약)
- `GET /api/v1/contracts`

> **폐기 (2026-07-27):** `GET /api/v1/risks`, `GET /api/v1/risks/{risk_id}`, `GET /api/v1/risks/{risk_id}/briefing`는 구현하지 않고 폐기한다. 리스크 목록은 `GET /api/v1/risk-events`(프론트 `RiskEvent` 계약, 데이터는 F3/F4 모델 배선 전까지 placeholder)가 대체하고, 브리핑 상세는 `GET /api/v1/briefings/{briefingId}`가 담당한다. `GET /api/v1/contracts/{contract_id}`(단건)도 소비 화면이 없어 보류(미구현)다.

목록 API는 `content`, `page`, `size`, `total_elements`, `total_pages` 페이지 구조를 사용한다. (구 `/risks` 상세의 `source`/`material`/`supplier`/`analysis`/`inventory` 응답 설계는 폐기 — 현재 리스크 계약은 프론트 `RiskEvent`: `market_context`/`erp_view`/`quality_check`/`rag_view`/`output_artifacts`.)

### 인증·인가 규칙

- 인증 없이 호출 가능: `signup`, `login`, `refresh`, `/api/v1/dashboard/**`, Swagger, Health
- JWT Access Token 필요: `logout`, `me`, 문서 API와 그 외 `/api/v1/**`
- Refresh Token은 `/refresh`에서만 사용하며 보호 API 인증에는 사용할 수 없다.
- 로그인 응답 필드: `access_token`, `refresh_token`, `token_type`, `expires_in`, `refresh_expires_in`
- 공개 회원가입에서 `PURCHASING`, `STRATEGY`, `EXECUTIVE` 역할을 선택할 수 있다.
- 로그아웃 시 같은 세션 ID의 Access/Refresh Token이 함께 무효화된다.
- 로그아웃 세션 ID와 만료 시각은 PostgreSQL `revoked_token_sessions`에 저장하며 서버 재시작 후에도 무효 상태를 유지한다.

### 실시간 관제 알림

`GET /api/v1/map/realtime-alerts`는 React의 지도, 알림 목록, 상세 근거 팝업에 필요한 데이터를 한 번에 반환한다.

- `coordinates`는 `[longitude, latitude]`, 즉 `[경도, 위도]` 순서다.
- 이 API는 조장 승인 화면 계약에 따라 공통 `success/data` Envelope를 사용하지 않는 예외 API다.
- `news_info.impact_domain`은 LLM 또는 Mock의 1차 도메인 값이다.
- `risk_assessment.final_level`은 `High`, `Medium`, `Low` 중 하나다.
- `ai_evidence`는 `event_features`와 1:1 매핑되는 모델 입력 Feature이며 SHAP 설명값이 아니다.
- `country_is_mining_hub`는 `1` 또는 `0`이다.
- `stock_volatility_20d`는 백분율이며 `14.5`는 `14.5%`를 의미한다.

```json
{
  "timestamp": "2026-07-21T11:45:00Z",
  "alerts": [
    {
      "event_id": 123456789,
      "country_code": "ID",
      "country_name_kr": "인도네시아",
      "coordinates": [113.9213, -0.7893],
      "affected_materials": ["Nickel"],
      "news_info": {
        "title": "인도네시아 술라웨시 니켈 광산 폭우로 조업 중단",
        "impact_domain": "생산",
        "summary_kr": "폭우로 니켈 채굴 및 제련 시설 가동이 중단됨.",
        "url": "https://example.com/mock-news/123456789"
      },
      "risk_assessment": {
        "final_level": "High",
        "ai_evidence": {
          "tone_score": -0.85,
          "goldstein_scale": -7.2,
          "news_count": 15,
          "country_is_mining_hub": 1,
          "rainfall_24h_mm": 185.0,
          "gdacs_alert_level": 2,
          "actor1_type": "GOV",
          "actor2_type": "BUS",
          "stock_volatility_20d": 14.5
        }
      }
    }
  ]
}
```

## FastAPI 통합 API

- `POST /api/v1/analyze`
- `POST /api/v1/rag/contracts` (`multipart/form-data`)
- `POST /api/v1/rag/search`

`/analyze` Mock은 실제 응답 스키마를 유지하고 `mock`, `mock_reason`을 명시한다. RAG 검색은 `contract_id` 또는 `supplier_id` 중 하나를 필수로 받고 `top_k` 범위는 1–20이다.

## FastAPI 내부 개발 API

- `POST /api/v1/internal/llm/extract`
- `POST /api/v1/internal/severity/score`
- `POST /api/v1/internal/briefings`

내부 API는 개발·Swagger 테스트용이며 React에서 직접 호출하지 않는다.

> **폐지 (2026-07-30) — `POST /api/v1/internal/ml/classify`**
>
> 이 엔드포인트는 제거됐다. 내부 구현이 XGBoost가 아니라 규칙 기반 mock이었고, Spring이 한 번도
> 호출하지 않았다. Impact Domain은 `/api/v1/internal/llm/extract`의 `impact_domain_draft`를
> 그대로 최종값으로 쓴다.
>
> 이 스펙을 참조 중인 쪽은 **이 항목 하나만 제외**하면 되고 나머지 계약은 변경이 없다.
> 특히 `/api/v1/analyze` 응답의 `classification` 필드(`impact_domain`·`confidence`·
> `model_version`·`mock`)는 **그대로 유지**된다 — 값을 채우는 주체가 mock 분류기에서
> LLM 추출로 바뀌었을 뿐이다. 분류 확률이 없는 경로이므로 `confidence`는 `null`이 온다.

## 데이터 소유권

| 데이터 | 쓰기 책임 | 읽기 |
|---|---|---|
| Material, Supplier, Inventory, Purchase Order, Contract | Spring Boot | Spring Boot, FastAPI |
| Risk Event, Feature, Classification, Severity | Spring Boot | Spring Boot |
| Briefing | Spring Boot | Spring Boot |
| Vector Document | FastAPI | FastAPI |

FastAPI는 분석 결과를 생성하여 Spring Boot에 반환하고, 영구 업무 데이터는 Spring Boot가 PostgreSQL에 저장한다.

## 문서 주소

- Spring: `http://localhost:8080/swagger-ui.html`
- FastAPI: `http://localhost:8000/docs`

변경 사항은 루트 `CHANGELOG.md`에 기록한다.
