# Codex 협업 대화 기록

- 프로젝트: 배터리 원자재 공급망 리스크 관제 시스템
- 작업 경로: `C:\aivleschool\bigproject\battery-risk-mvp-starter`
- 정리 기준일: 2026-07-22
- 작성 목적: 지금까지 논의한 설계 결정, 팀 역할, 구현 내역과 다음 작업을 한 문서에서 확인하기 위함

> 이 문서는 채팅 문장 전체를 그대로 옮긴 축어록이 아니다. 대화에서 확인된 사용자 요청, 합의된 결정, 실제 코드 변경과 검증 결과를 시간순·주제별로 재구성한 협업 기록이다. 첨부 이미지와 과거 답변 전문은 별도 보관되지 않았으므로 확인 가능한 대화 및 프로젝트 상태를 기준으로 작성했다.

---

## 1. 초기 요청과 프로젝트 진단

처음에는 데이터·모델링·백엔드 To Do List와 Interface Specification을 기준으로 1순위까지 충실하게 구현됐는지 분석하고 부족한 부분을 보완하는 작업을 진행했다.

초기 보완 대상으로 다음 파일과 계층이 논의됐다.

```text
core/config.py
개별 extraction/classification/severity 서비스
ml/inference.py
rag/loader.py, chunker.py, vector_store.py
repository 계층
실제 ERP Context 서비스
```

다만 실제 LLM·XGBoost·Embedding 모델은 아직 전달되지 않았으므로, 모델 연동은 보류하고 동일한 API Schema를 사용하는 Mock/Adapter 구조를 먼저 완성하기로 했다.

---

## 2. 중첩 프로젝트와 Git 정리

프로젝트를 복사·붙여넣는 과정에서 `CHANGELOG.md`, 문서, 테스트와 설정 파일이 바깥 프로젝트와 중첩 프로젝트에 나뉘어 있는 문제가 발견됐다.

정리 방향은 다음과 같았다.

1. 바깥에만 있는 파일의 기능 검토
2. 내용이 다른 파일 병합
3. 안쪽의 최신 문서·테스트·설정을 바깥 루트로 이동
4. 병합된 바깥 프로젝트에서 양쪽 테스트 실행
5. 중첩 프로젝트를 별도 백업 폴더로 이동
6. 정상 동작 확인 후 중첩 복사본 제거
7. 바깥 루트에서 Git 저장소 정상화

`project-backups`, `_merge_backup`은 정상 동작과 Git 상태를 확인한 뒤 삭제해도 된다는 방향으로 논의했다. 목표는 복구 가능성을 먼저 확보한 뒤 최종 루트를 깔끔하게 유지하는 것이었다.

---

## 3. 전체 아키텍처 확정

초기에는 FastAPI가 AI 파이프라인 전체를 직접 제공하는 구조도 검토했다. 이후 React, 데이터 저장, 인증, ERP 책임을 고려하여 다음 구조로 확정했다.

```text
React
  ↓ 외부 API
Spring Boot
  ├─ 전체 호출 관리
  ├─ PostgreSQL 읽기·쓰기
  ├─ 사용자 인증·권한
  ├─ 원본 파일과 처리 상태 관리
  ├─ ERP Context 구성과 결정적 수치 계산
  └─ 결과 저장·조회 API
       ↓ 내부 API
FastAPI
  ├─ PDF/TXT 추출
  ├─ 청킹
  ├─ Embedding·ChromaDB
  ├─ XGBoost Impact Domain 분류 예정
  ├─ Severity Rule Engine
  └─ RAG·브리핑 분석
```

핵심 원칙:

- React는 Spring Boot만 호출한다.
- React는 FastAPI 내부 API를 직접 호출하지 않는다.
- Spring Boot만 PostgreSQL에 접근한다.
- FastAPI는 PostgreSQL에 접근하지 않는다.
- FastAPI가 ChromaDB 읽기·쓰기를 전담한다.
- 실제 모델이 없을 때도 같은 요청·응답 Schema를 유지한다.
- 모든 팀 JSON 필드명은 `snake_case`로 통일한다.
- Mock 결과는 실제 AI 결과로 오해되지 않도록 반드시 표시한다.

---

## 4. FastAPI 기본 구조와 초기 완료 기준

FastAPI 폴더 구조는 다음 규칙을 유지하기로 했다.

```text
fastapi-ai/
├─ app/
│  ├─ api/
│  ├─ core/
│  ├─ crud/
│  ├─ models/
│  ├─ repositories/
│  ├─ schemas/
│  └─ services/
├─ tests/
└─ requirements.txt
```

초기 FastAPI 완료 기준:

- Schema를 도메인별 파일로 분리
- 내부 API의 구체적인 Request/Response Model
- 입력 기반 Extraction Mock
- 여러 Impact Domain Classification Mock
- NORMAL/WARNING/CRITICAL Severity 시나리오
- `/analyze`의 모든 옵션이 흐름을 실제로 제어
- 업로드→검색 RAG Mock E2E
- In-memory 테스트 격리
- `core/exceptions.py` 공통 오류 처리
- `api/dependencies.py` 의존성 주입
- OpenAPI Request/Response Schema 검증
- `/docs` 실행 확인
- 전체 테스트 통과

---

## 5. F·C·M·D 기능 분류

기획정의서와 모델링 정의서를 바탕으로 기능을 네 범주로 정리했다.

### F 기능

업무 사용자에게 제공되는 핵심 서비스 기능이다.

- F1: ERP 영향 분석 AI 에이전트
- F2: RAG 기반 계약·대체 공급망 분석
- F3: AI 기반 공급망 리스크 분석
- F5: 구매 브리핑·보고서
- F6: ERP Master Data 및 공통 기반

### C 기능

서비스 운영을 위한 공통 백엔드 기능이다.

- C1: 계약서·지침 파일 업로드
- C2: 회원가입·로그인·인증·권한
- 기타 공통 오류·보안·처리 기능

### M 기능

모델 학습, 라벨링, 평가와 모니터링 관련 기능이다. 모델 담당자가 로컬에서 수행하는 항목은 백엔드에서 제외하거나 보류했다.

### D 기능

데이터 수집, 품질, 신뢰도와 관련된 기능이다. 팀이 명확한 기준을 갖지 못했거나 로컬 검증으로 충분한 항목은 삭제·보류했다.

---

## 6. 삭제·보류 기능 판단

다음 의견의 타당성을 검토했다.

- F12: 부가성이 높아 삭제
- C3: 근거 부족 시 종료와 최대 2회 추가 탐색은 시간이 있을 때 구현
- C4: 프로젝트를 무겁게 만들 가능성이 높아 삭제
- C7: 출력 형식 오류, 환각, Prompt Injection 방어가 백엔드 관리 대상인지 확인 후 유지 여부 결정
- M1·M2·M4·M5·M6: 모델 담당자의 로컬 작업이므로 백엔드 범위에서 삭제
- M3: M1·M2 삭제에 따라 보류 또는 삭제
- D1: 보류
- D2: 뉴스 신뢰도를 판정할 합의된 기준이 없어 삭제
- D3: 로컬 데이터 검증으로 처리하므로 삭제
- D4: 원천 데이터를 그대로 사용한다면 별도 백엔드 기능 필요성을 재검토

결론은 “발표용·운영용으로 반드시 필요한 기능”과 “로컬 분석 또는 향후 고도화 기능”을 분리하여 MVP를 무겁게 만들지 않는 것이었다.

---

## 7. 팀 역할과 협업 규칙

사용자의 역할은 김민지 담당 범위로 정리됐다.

```text
김민지
→ Spring Boot 핵심 인프라와 외부 API
→ 공통 PostgreSQL 기반
→ C1 문서 Metadata·업로드
→ Spring–FastAPI 통합
→ ERP 저장·조회·영향 계산
```

다른 팀원과의 경계:

### C2 담당자

- Spring Security
- JWT Access/Refresh Token
- 회원가입·로그인·로그아웃
- 사용자 Schema Migration

### 모델 담당자

- XGBoost 학습
- 학습 데이터·Feature 검증
- 최종 모델 파일 전달

### RAG·VectorDB 담당자

- ChromaDB 저장·조회·삭제 기반
- Collection과 Vector Store 구현

공통 규칙:

1. 공통 DB는 PostgreSQL을 사용한다.
2. DataSource와 Docker는 김민지가 구성한다.
3. C2는 별도 MySQL/H2 운영 설정을 만들지 않는다.
4. Migration 번호를 사전에 예약한다.
5. `build.gradle`, `application.yml` 변경을 공유한다.
6. `GlobalExceptionHandler` 수정 전 서로 확인한다.
7. React 외부 인증 API는 Spring Boot가 제공한다.
8. FastAPI에는 회원가입·로그인 기능을 만들지 않는다.

---

## 8. 파일 수 최소화 원칙

하나의 기능을 구현할 때 파일이 지나치게 많아지지 않도록, 특별한 이유가 없다면 각 폴더에 기능별 파일 하나 정도만 추가하기로 했다.

Spring C1 예시:

```text
config/FastApiConfig.java
controller/DocumentController.java
dto/DocumentDto.java
exception/GlobalExceptionHandler.java
service/DocumentService.java
```

FastAPI C1 예시:

```text
api/v1/documents.py
schemas/document.py
services/document_service.py
```

다만 DB Migration, 테스트와 기존 공통 파일 수정은 별도로 필요할 수 있다.

---

## 9. C1 파일 업로드 설계와 C1-A

### 목표

계약서와 구매 지침 PDF/TXT를 안전하게 접수하고 RAG 처리로 연결한다.

### Spring 책임

- Multipart 요청 접수
- 확장자·MIME·크기 검사
- 파일명 정리와 경로 이동 공격 방지
- SHA-256 계산과 중복 확인
- `document_id` 발급
- 원본 파일 Volume 저장
- PostgreSQL 상태 저장
- FastAPI 문서 처리 호출

### FastAPI 책임

- PDF/TXT 텍스트 추출
- 계약 조항 우선 청킹
- Embedding 생성
- ChromaDB 적재
- 처리 결과·오류 반환

C1-A 처리 흐름:

```text
Multipart 접수
→ 기본 검증
→ SHA-256 계산
→ PostgreSQL 중복 확인
→ UUID document_id 발급
→ 원본 파일 저장
→ PostgreSQL PENDING
→ PROCESSING
→ FastAPI 호출
→ 성공 시 COMPLETED
→ 실패 시 FAILED
```

원본 저장 구조:

```text
uploads/
└─ contracts/
   └─ {document_id}/
      └─ original.pdf 또는 original.txt
```

C1-A는 PostgreSQL Metadata 영구 저장, 원본 파일 보존, 중복 차단과 상태 조회까지 구현됐다. RAG 검색까지 끝난 상태를 뜻하지는 않는다.

---

## 10. PostgreSQL과 최소 문서 Schema

PostgreSQL을 Spring Boot만 소유하도록 구성했다.

- PostgreSQL 16 Docker
- Named Volume
- Spring Data JPA
- Flyway
- `ddl-auto: validate`
- Actuator Health Check

초기 Master와 문서 테이블:

```text
materials
suppliers
contracts
contract_documents
```

문서 처리 상태:

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

PostgreSQL 연결 후 다음 Health 응답을 확인했다.

```json
{
  "status": "UP"
}
```

---

## 11. C2 인증·권한 통합

C2 담당자의 Spring 코드를 C1-A 기반 프로젝트에 통합했다.

회원 역할:

```text
PURCHASING: 구매팀
STRATEGY: 경영기획팀
EXECUTIVE: 경영진
```

검증한 흐름:

1. 회원가입→로그인→`/me`→로그아웃
2. Access/Refresh Token 분리
3. Refresh 후 새 Access Token 발급
4. 로그아웃 후 Access/Refresh 동시 무효화
5. 잘못된 비밀번호 오류
6. 공개 Dashboard와 보호 API 구분

사용자와 로그아웃 토큰 세션은 In-memory가 아니라 PostgreSQL에 저장하도록 연결했다.

---

## 12. 프론트엔드 공통 실시간 관제 Schema

프론트엔드가 지도, 알람 목록과 AI 판단 근거를 렌더링할 수 있도록 다음 구조를 공통 계약으로 논의했다.

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
        "title": "뉴스 제목",
        "impact_domain": "생산",
        "summary_kr": "뉴스 요약",
        "url": "https://example.com"
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

기존 단건 Risk 더미 응답 대신 위 관제 Schema를 우선시하고, 팀 전체 필드는 `snake_case`로 변경하기로 했다.

---

## 13. 최종 개발 로드맵

```text
0. 아키텍처·API 계약 확정
1. PostgreSQL 기본 환경
2. F6 최소 Master Data·문서 Schema
3. C1-A Spring 업로드·영구 저장
4. FastAPI 문서 추출·청킹
5. ChromaDB 연결
6. Mock Embedding·Metadata Filter
7. C1-B 업로드·적재·검색 실제 E2E
8. React 파일 업로드 연결
9. ERP Mock Data
10. F1 ERP 영향 계산
11. Severity Rule Engine
12. F2 RAG Mock 대응 분석
13. F5 템플릿 브리핑
14. 전체 조회 API·React 연결
15. Docker 통합·장애 검증
16. 실제 모델 Adapter 교체
```

단계 번호는 의존 관계를 보여주지만, 팀원이 5단계를 작업하는 동안 충돌하지 않는 Spring F6/F1이나 Severity를 병렬로 진행할 수 있다고 판단했다.

---

## 14. 4단계 문서 추출·청킹

구현 사항:

- Spring이 발급한 `document_id` 사용
- 파일명 기반 ID 생성 제거
- PDF 페이지 번호 보존
- TXT 인코딩 오류 구분
- 손상 PDF 오류 구분
- 빈 문서 오류 구분
- 청크 인덱스 생성
- 계약 조항 우선 청킹

청킹 우선순위:

```text
1. 조항 번호
2. 문단
3. 너무 긴 문단만 길이 기준 분할
```

청크 규격:

```text
document_id
chunk_index
page_number
content
contract_id
supplier_id
material_id
document_type
content_hash
```

`content_hash`는 청크별 Hash가 아니라 원본 파일 SHA-256이며 같은 문서의 모든 청크가 같은 값을 사용한다.

---

## 15. 5단계 ChromaDB 팀 협업

태희님이 ChromaDB/VectorDB 구현을 담당하기로 했다. 사용자는 4단계 청킹을 진행하고, 태희님은 Chroma 저장·검색을 진행하는 방식으로 역할을 나눴다.

청킹이 중복되지 않도록 다음 내용을 공유했다.

> 저는 6단계 Mock Embedding을 구현하겠습니다. ChromaDB 코드에서 OpenAIEmbeddings를 함수 내부에 직접 고정하지 말고, 외부에서 Embedding 객체를 전달받을 수 있게 구성해 주세요. `EMBEDDING_PROVIDER=mock|openai`로 교체하고, Mock과 OpenAI 모두 `embed_documents()`와 `embed_query()` 규격을 사용하면 통합하기 쉽습니다. 4단계 청크의 `content`를 임베딩하고 나머지는 Chroma Metadata로 저장해 주세요.

Chroma Metadata는 팀 규칙에 맞춰 다음 `snake_case`를 사용하기로 했다.

```text
document_id
contract_id
supplier_id
material_id
document_type
chunk_index
page_number
content_hash
embedding_type
embedding_version
mock_embedding
```

현재 정확한 상태:

```text
4단계 문서 추출·청킹: 완료
5단계 ChromaDB: 태희님 수정본 대기 중
6-A Mock Embedding: 완료
6-B Chroma Metadata 저장: 5단계 병합 후 진행
6-C Metadata Filter 검색: 5단계 병합 후 진행
7단계 실제 E2E: 5·6 통합 후 진행
```

따라서 현재 프로젝트가 ChromaDB 최종 통합을 완료했다고 판단하면 안 된다.

---

## 16. 6단계 Mock Embedding

실제 OpenAI Embedding을 사용할 수 없는 상태에서 토큰 Hash 기반 Mock Embedding을 구현했다.

```text
문장 토큰화
→ 토큰별 SHA-256 Hash
→ 고정 차원 Vector에 누적
→ Vector 정규화
```

Provider 계약:

```python
embed_documents(texts: list[str]) -> list[list[float]]
embed_query(text: str) -> list[float]
```

식별 정보:

```json
{
  "embedding_type": "MOCK_TOKEN_HASH",
  "embedding_version": "mock-v1",
  "mock_embedding": true
}
```

실제 OpenAI API Key는 코드에 작성하지 않고 환경변수로만 전달하며, Mock과 OpenAI Vector는 같은 Collection에 섞지 않기로 했다.

---

## 17. ERP Mock CSV와 데이터 검토

팀원에게 다음 두 종류의 ERP CSV를 받았다.

```text
spring-csv
→ PostgreSQL Seed용 원천 데이터

agent-csv
→ 계산 결과 검증 Fixture
```

`agent-csv/03_erp_purchase_order_context.csv`에는 날짜를 계산할 때 입고 시각까지 포함한 차이를 올림한 뒤 CSV에는 날짜만 저장하여 1일 크게 나온 오류가 있었다. 팀원이 수정본을 다시 전달했고 수정된 파일을 기준으로 사용하기로 했다.

CSV를 다시 만들어 달라고 요청할 정도의 구조적 문제는 아니며, 원천과 검증 Fixture의 역할을 분리하여 사용하기로 했다.

---

## 18. F6 ERP Master Schema·외부 ID 매핑

태희님의 ChromaDB 수정본을 기다리는 동안 충돌하지 않는 Spring 영역으로 F6와 F1을 진행했다.

외부 ERP의 문자열 ID와 내부 DB PK를 분리했다.

```text
외부 ERP ID: MAT-LI-CARB, SUP-CHL-01, CTR-001 등
내부 PK: BIGINT/BIGSERIAL
```

V4 Migration으로 다음을 구현했다.

- 자재·공급사·계약 외부 ID
- 창고
- 공급사-자재 관계
- 재고 Snapshot
- 자재 소비량
- 발주
- 발주 품목
- 입고
- FK, Unique, Check Constraint와 Index

CSV Seed는 `ERP_SEED_ENABLED=true`일 때만 실행하며 `00_manifest.csv`의 순서와 행 수를 검증한 뒤 한 트랜잭션으로 upsert한다.

실제 PostgreSQL 적재 결과:

| 데이터 | ERP 행 수 |
| --- | ---: |
| 자재 | 10 |
| 공급사 | 18 |
| 창고 | 4 |
| 계약 | 28 |
| 공급사-자재 | 28 |
| 재고 | 120 |
| 소비량 | 30 |
| 발주 | 90 |
| 발주 품목 | 111 |
| 입고 | 84 |
| 합계 | 523 |

---

## 19. F1 Spring ERP Context와 결정적 계산

Spring 외부 API:

```http
POST /api/v1/erp/context
```

계산 항목:

- 가용재고
- 평균 일사용량
- 재고 소진 일수
- 안전재고 일수와 부족량
- 다음 입고일과 남은 일수
- 예상 공급 공백
- 미입고 수량
- 공급사 의존도
- 대체 공급사 승인 상태
- FEOC 상태
- 데이터 품질 상태

실제 리튬 ERP 데이터 검증 결과:

```text
가용재고: 36,000kg
재고일수: 36일
안전재고일수: 15일
다음 입고: 8일
공급사 의존도: 45%
미입고 수량: 134,900kg
```

계약 가격 조정 임계치 계산은 현재 CSV에 임계치·시장 가격 데이터가 없어 임의 구현하지 않았다. 실제 ERP 연동과 FastAPI 설명 생성도 후속 범위다.

---

## 20. 11단계 Severity Rule Engine

태희님 ChromaDB 수정본을 기다리는 동안 Chroma/RAG와 겹치지 않는 Severity Rule Engine을 구현했다.

전체 흐름:

```text
React
→ Spring Severity API
→ F1 ERP Context 조회
→ FastAPI 결정적 Severity 계산
→ PostgreSQL severity_assessments 저장
→ Spring 결과 조회
```

Spring 외부 API:

```http
POST /api/v1/severity/assessments
GET  /api/v1/severity/assessments/{assessment_id}
```

FastAPI 내부 API:

```http
POST /api/v1/internal/severity/score
```

입력:

```text
inventory_days
safety_stock_days
expected_supply_gap_days
supplier_dependency_ratio
price_change_rate
logistics_delay_days
gdacs_alert_level
feoc_status
data_quality_status
```

등급 기준:

```text
NORMAL: 30점 미만
WARNING: 30점 이상 70점 미만
CRITICAL: 70점 이상
UNKNOWN: 사용 가능한 수치가 없거나 data_quality_status=INVALID
```

Hard Gate:

```text
feoc_status=YES
→ score=100
→ severity=CRITICAL
→ reason_codes=[FEOC_HARD_GATE]
```

FastAPI의 중복된 `models/severity_engine.py`는 제거하고 `services/severity_service.py`를 단일 규칙 구현으로 정리했다.

실제 E2E 결과:

```text
일반 리튬 시나리오
→ 67점 WARNING
→ 저장 후 GET 재조회 성공

FEOC 공급사 시나리오
→ 100점 CRITICAL
→ FEOC_HARD_GATE
→ forced_critical=true
```

검증용 사용자와 Severity 결과 행은 테스트 후 삭제했다.

---

## 21. 현재 테스트 결과

11단계 완료 시점의 전체 결과:

```text
FastAPI: 51 passed
Spring Boot: 19 passed
PostgreSQL V4 적용: 성공
PostgreSQL V5 적용: 성공
F1 실제 HTTP E2E: 성공
Severity 실제 HTTP·저장·조회 E2E: 성공
```

V5 테이블:

```text
severity_assessments
```

임시 FastAPI·Spring 검증 서버는 종료했고 PostgreSQL의 검증용 Severity 행도 정리했다.

---

## 22. 현재 완료 상태

| 단계/기능 | 상태 |
| --- | --- |
| PostgreSQL 기본 환경 | 완료 |
| C1-A 업로드·영구 저장 | 완료 |
| C2 인증·PostgreSQL 통합 | 완료 |
| 4단계 문서 추출·청킹 | 완료 |
| 5단계 ChromaDB | 완료 |
| 6-A Mock Embedding | 완료 |
| 6-B Chroma Metadata 저장 | 완료 |
| 6-C Metadata Filter 검색 | 완료 |
| F6 ERP Master·외부 ID 매핑 | 완료 |
| F1 Spring ERP 결정적 계산 | 완료 |
| 11단계 Severity Rule Engine | 완료 |
| 7단계 C1-B 실제 E2E | 완료 |
| 실제 OpenAI Embedding | 보류 |
| 실제 LLM·XGBoost | 모델 전달 전까지 보류 |

---

## 23. 다음 작업

4·5·6단계 통합과 7단계 실제 E2E를 완료했다. 다음 순서로 진행한다.

```text
1. 8단계 React 업로드 화면 연결
2. 업로드 중·완료·실패·중복 상태 표시
3. Spring 외부 RAG 검색 API 연결
4. 계약·공급사·자재 필터 UI 검증
5. 9단계 ERP Mock Data 후속 기능 진행
```

실제 OpenAI Embedding과 LLM은 모델 전달 전까지 보류하고 현재 API Schema를 유지한다.

---

## 24. 현재 프로젝트를 설명하는 한 문장

현재 프로젝트는 실제 LLM·XGBoost·OpenAI Embedding이 없는 상태에서도 Spring의 PostgreSQL·인증·파일·ERP Context와 FastAPI의 문서 처리·Mock Embedding·결정적 Severity 인터페이스를 검증할 수 있으며, 다음 핵심 통합 작업은 태희님의 ChromaDB 수정본을 병합하여 업로드부터 검색까지 실제 E2E를 완성하는 것이다.

---

## 25. 요청별 상세 대화 기록

> 아래 기록은 현재 대화에서 확인 가능한 사용자 요청을 발생 순서대로 정리한 것이다. `결론`, `수행 내용`, `결과`는 당시 답변과 실제 프로젝트 상태를 요약한 것이며, 보존되지 않은 과거 Codex 답변을 축어적으로 복원한 내용은 아니다.

### 요청 1 — 1순위 구현 충족 분석

- **사용자 요청:** 첨부한 데이터·모델링·백엔드 To Do List와 Interface Specification을 기준으로 현재 코드가 1순위까지 충실하게 구현됐는지 분석해 달라고 요청했다.
- **결론:** 일부 구조와 Mock API는 있었지만 계층 분리, 실제 흐름 제어와 검증이 부족했다.
- **수행 내용:** 문서 기준과 코드 구조를 비교하고 부족한 파일·서비스·테스트를 분류했다.
- **결과:** 1순위 완료 판정 전에 보완이 필요하다는 방향이 정해졌다.

### 요청 2 — 부족한 구현 보완

- **사용자 요청:** 앞선 분석에서 발견한 부족한 부분을 보완해 달라고 요청했다.
- **결론:** 기존 API 계약을 유지하면서 누락된 Schema, Service, 공통 처리와 테스트를 보강해야 했다.
- **수행 내용:** 보완 범위를 정리하고 관련 코드 변경을 진행했다.
- **결과:** 초기 FastAPI Mock 인터페이스의 완성도가 높아졌다.

### 요청 3 — To Do List 충족 범위 재판정

- **사용자 요청:** 새로 첨부한 To Do List를 기준으로 어느 부분까지 충족했는지 다시 확인해 달라고 요청했다.
- **결론:** 문서의 항목별 완료·부분 완료·미완료를 분리해야 했다.
- **수행 내용:** 구현 파일과 API를 To Do 항목에 매핑했다.
- **결과:** 다음 우선 작업을 계층 기반 보완으로 좁혔다.

### 요청 4 — FastAPI 계층 생성, 실제 모델 연동 보류

- **사용자 요청:** `core/config.py`, extraction/classification/severity 서비스, `ml/inference.py`, RAG 파일, Repository 계층과 ERP Context 서비스를 만들되 실제 모델·LLM·RAG 연동은 보류하겠다고 했다.
- **결론:** Adapter와 Mock 경계를 먼저 만드는 방식이 적절했다.
- **수행 내용:** 교체 가능한 서비스·Repository·모델·RAG 경계를 설계했다.
- **결과:** 실제 모델 없이도 API 흐름을 테스트할 수 있는 구조를 마련했다.

### 요청 5 — 복사·붙여넣기로 뒤섞인 프로젝트 분석

- **사용자 요청:** `CHANGELOG.md` 등의 파일 위치와 프로젝트 구조가 뒤죽박죽인 원인을 분석해 달라고 요청했다.
- **결론:** 바깥 프로젝트와 중첩 복사본의 파일 차이를 비교해야 했다.
- **수행 내용:** 중복·단독·내용 상이 파일과 Git 루트를 구분했다.
- **결과:** 병합·백업·삭제·Git 정상화 절차가 제안됐다.

### 요청 6 — 중첩 프로젝트 병합과 Git 정상화

- **사용자 요청:** 바깥 전용 파일 검토, 19개 파일 병합, 최신 문서·테스트 이동, 양쪽 테스트, 백업, 중첩 복사본 제거와 Git 초기화를 진행해 달라고 요청했다.
- **결론:** 복구 가능한 백업을 유지하며 바깥 루트를 최종 프로젝트로 만드는 것이 안전했다.
- **수행 내용:** 파일 비교·병합·검증 순서를 적용했다.
- **결과:** 바깥 루트 중심의 프로젝트 정리 방향이 확정됐다.

### 요청 7 — 백업 폴더 삭제 판단

- **사용자 요청:** `project-backups`, `_merge_backup`을 깔끔하게 삭제해도 되는지 판단을 요청했다.
- **결론:** 병합본 테스트와 Git 상태를 확인한 뒤 삭제하는 것이 적절했다.
- **수행 내용:** 두 폴더의 복구 가치와 삭제 시점을 설명했다.
- **결과:** 정상 동작 확인 후 제거한다는 기준을 세웠다.

### 요청 8 — 두 FastAPI 코드 비교

- **사용자 요청:** 별도 `fastapi-ai` 폴더와 현재 코드 중 FastAPI 서버 구축·Swagger 기준을 더 잘 충족하는 쪽을 비교해 달라고 요청했다.
- **결론:** 폴더 존재만이 아니라 API Schema, Mock E2E, Swagger와 테스트를 기준으로 비교해야 했다.
- **수행 내용:** 권장 구조, 7개 API, camel/snake 규칙과 완료 기준을 대조했다.
- **결과:** 장점은 병합하고 부족한 계약·테스트는 보완하는 방향이 정해졌다.

### 요청 9 — FastAPI 100% 충족에 필요한 기능

- **사용자 요청:** 두 FastAPI 구현 외에 무엇을 더 만들어야 완료 기준을 100% 충족하는지 물었다.
- **결론:** 구체적 Schema, 입력 기반 Mock, 옵션 흐름, RAG E2E, 공통 오류와 OpenAPI 검증이 필요했다.
- **수행 내용:** 누락 기능을 체크리스트로 정리했다.
- **결과:** 이후 코드 수정의 명확한 완료 기준이 생겼다.

### 요청 10 — FastAPI 완료 체크리스트 전체 구현

- **사용자 요청:** Schema 분리, dict 응답 제거, 다양한 Mock 시나리오, 옵션 흐름, RAG E2E, 테스트 격리, 오류 처리, DI, OpenAPI와 전체 테스트를 구현해 달라고 요청했다.
- **결론:** 기존 API를 도메인 Schema와 Service로 분리하고 테스트로 고정해야 했다.
- **수행 내용:** FastAPI 코드와 테스트를 수정했다.
- **결과:** 실제 모델이 없어도 Swagger에서 전체 Mock 분석 인터페이스를 검증할 수 있게 됐다.

### 요청 11 — 1·2순위와 Spring/FastAPI 구현 범위 분석

- **사용자 요청:** To Do List의 1·2순위를 모두 만족하는지, Spring Boot와 FastAPI가 어디까지 구현됐는지 정리해 달라고 요청했다.
- **결론:** 완료와 Mock 완료, 실제 연동 미완료를 구분해야 했다.
- **수행 내용:** 서비스별 책임과 API·저장·모델 상태를 분류했다.
- **결과:** 이후 우선순위와 팀 공유에 사용할 구현 현황이 정리됐다.

### 요청 12 — React 역할과 잘못된 작업 디렉터리 오류

- **사용자 요청:** React는 프론트 코드라 백엔드에서 할 일이 없는지, 존재하지 않는 작업 디렉터리 오류를 어떻게 해결하는지 물었다.
- **결론:** 백엔드는 API 계약과 더미 응답을 제공할 수 있으며, 오류는 프로젝트 이동으로 사라진 경로 설정 문제였다.
- **수행 내용:** IDE/터미널의 Working Directory를 실제 루트로 변경하는 방법을 설명했다.
- **결과:** React와의 계약 작업 및 실행 경로 수정 방향을 이해했다.

### 요청 13 — FastAPI 실행 방법

- **사용자 요청:** FastAPI를 어떻게 실행하는지 물었다.
- **결론:** 가상환경 활성화, 의존성 설치와 Uvicorn 실행이 필요했다.
- **수행 내용:** 프로젝트 경로와 실행 순서를 안내했다.
- **결과:** `/docs`를 열 수 있는 기본 실행 절차를 확보했다.

### 요청 14 — Python 터미널 명령어

- **사용자 요청:** Python 터미널에서 실행할 정확한 명령어를 요청했다.
- **결론:** Windows PowerShell 기준 명령이 필요했다.
- **수행 내용:** venv 활성화, `pip install`, `uvicorn app.main:app` 명령을 제공했다.
- **결과:** 직접 실행 가능한 명령을 확보했다.

### 요청 15 — FastAPI 구조 설명

- **사용자 요청:** FastAPI의 폴더 구조와 각 계층의 역할을 설명해 달라고 요청했다.
- **결론:** API→Service→Model/Repository와 Schema·Core 책임을 분리해 설명해야 했다.
- **수행 내용:** 요청 흐름과 각 폴더의 목적을 쉬운 표현으로 정리했다.
- **결과:** 구조를 팀원에게 설명할 수 있는 기준이 생겼다.

### 요청 16 — React 우선 연결 여부

- **사용자 요청:** React와 먼저 연결한 후 검증하는 것이 좋은지 물었다.
- **결론:** 전체 기능 완료를 기다리기보다 최소 API 계약을 먼저 연결하는 것이 좋았다.
- **수행 내용:** Swagger와 Interface Specification으로 화면 필드를 조기 검증하는 방안을 제안했다.
- **결과:** 프론트 계약을 일찍 고정하는 방향이 채택됐다.

### 요청 17 — React 연결과 1~5순위 개발 순서

- **사용자 요청:** 지금 React 구조를 맞출지, 1~5순위를 모두 만든 뒤 연결할지 물었다.
- **결론:** 핵심 API 계약을 먼저 맞추고 나머지 기능을 진행하는 것이 안전했다.
- **수행 내용:** Mock API 연결→필드 검토→후속 기능 개발 순서를 제안했다.
- **결과:** 백엔드와 프론트의 재작업 위험을 줄이는 순서가 정해졌다.

### 요청 18 — 프론트 협의 후 3~5순위 개발 확인

- **사용자 요청:** 프론트팀과 코드·구조를 먼저 협의한 뒤 3~5순위를 개발하면 되는지 확인했다.
- **결론:** 그 순서가 적절했다.
- **수행 내용:** API 계약, 화면 상태와 Mock 표시를 먼저 합의할 항목으로 정리했다.
- **결과:** 팀 협업 순서가 명확해졌다.

### 요청 19 — 화면만 있는 프론트와 연동 가능성

- **사용자 요청:** 프론트가 화면만 만든 상태에서도 Swagger와 Interface Specification으로 필드·상태를 검토받을 수 있는지 물었다.
- **결론:** 실제 DB가 없어도 충분히 가능했다.
- **수행 내용:** Dummy JSON, 로딩·성공·실패·빈 상태를 검토 항목으로 제시했다.
- **결과:** 프론트가 API를 가정해 연결 작업을 시작할 수 있게 됐다.

### 요청 20 — Spring 오케스트레이션·FastAPI 분석 구조 판단

- **사용자 요청:** Spring이 전체 호출·DB·조회 API를 담당하고 FastAPI가 LLM/XGBoost·심각도 분석을 담당하는 구조가 좋은지 물었다.
- **결론:** 저장 책임과 AI 계산 책임이 분명해져 더 적절했다.
- **수행 내용:** 서비스 책임과 호출 방향을 재정의했다.
- **결과:** 현재의 기본 아키텍처가 확정됐다.

### 요청 21 — To Do List를 새 아키텍처로 수정

- **사용자 요청:** 확정한 Spring/FastAPI 책임에 맞게 To Do List를 수정해 달라고 요청했다.
- **결론:** 기능별로 Spring·FastAPI 책임을 나눠 문서화해야 했다.
- **수행 내용:** To Do 항목과 완료 기준을 새 구조로 재작성했다.
- **결과:** 팀 역할과 구현 순서가 일관된 문서가 마련됐다.

### 요청 22 — 이미지 속 김민지 역할과 추가 기능 목록

- **사용자 요청:** 이미지의 김민지 역할을 기준으로 Spring 기능을 재정의하고 ERP, RAG, 모니터링, 실시간 데이터, 브리핑, 파일 업로드와 인증 기능 목록을 요청했다.
- **결론:** 구현 전에 기능·책임·우선순위를 먼저 확정해야 했다.
- **수행 내용:** 핵심 기능과 공통 기능을 목록화하고 팀 경계를 정리했다.
- **결과:** 김민지 담당 백엔드 범위가 구체화됐다.

### 요청 23 — 기존 To Do 문서에 필수 기능 구성

- **사용자 요청:** `backend-data-modeling-todo-v0.2.md`에 필수 기능을 어떻게 구성할지 물었다.
- **결론:** 기능 코드, 목표, 책임, 완료 기준과 의존 순서를 갖춘 문서가 필요했다.
- **수행 내용:** F/C/M/D 섹션과 단계별 로드맵 구성을 제안했다.
- **결과:** 문서를 확장할 구조가 정해졌다.

### 요청 24 — 기획·모델링 정의서 기반 추가 기능 제안

- **사용자 요청:** 두 정의서를 바탕으로 더 구현해야 할 기능을 제시해 달라고 요청했다.
- **결론:** 데이터 품질, 근거, 버전, 상태, 실패 처리 같은 교차 기능이 추가로 필요했다.
- **수행 내용:** 정의서 문구를 기능 요구사항으로 변환했다.
- **결과:** F·C·M·D 후보 기능이 확장됐다.

### 요청 25 — 전체 To Do Markdown·Notion 형식 생성

- **사용자 요청:** 기존 기능과 추가 기능을 합쳐 전체 To Do를 Markdown 파일과 Notion 붙여넣기 형식으로 만들어 달라고 요청했다.
- **결론:** 개발용 상세본과 협업용 간결본이 모두 필요했다.
- **수행 내용:** 두 형식의 문서를 구성했다.
- **결과:** 프로젝트 To Do 문서가 버전 관리 가능한 형태로 정리됐다.

### 요청 26 — F6 이후 기능의 근거 설명

- **사용자 요청:** F6 이후 기능을 왜 설정했는지, 정의서의 어떤 내용을 바탕으로 했는지 쉽게 설명해 달라고 요청했다.
- **결론:** 각 기능은 데이터 연결, 근거 보존, 운영 안전성을 정의서 요구에 대응시킨 것이었다.
- **수행 내용:** 기능별 정의서 근거와 쉬운 예시를 설명했다.
- **결과:** 기능이 임의로 추가된 것이 아니라 요구사항에서 파생됐음을 확인했다.

### 요청 27 — C·M·D 기능 설명

- **사용자 요청:** C, M, D 기능이 각각 무엇인지 설명해 달라고 요청했다.
- **결론:** 공통 백엔드, 모델 운영, 데이터 운영의 차이를 구분해야 했다.
- **수행 내용:** 각 코드군의 목적과 예시를 설명했다.
- **결과:** 기능 분류를 회의에서 설명할 수 있게 됐다.

### 요청 28 — 삭제·보류 판단의 타당성 검토

- **사용자 요청:** F12, C3, C4, 일부 M·D 기능을 삭제·보류하려는 근거가 납득 가능한지 판단해 달라고 요청했다.
- **결론:** MVP 무게와 팀 역할을 고려할 때 대부분 합리적이지만, 삭제 사유와 후속 조건을 문서에 남겨야 했다.
- **수행 내용:** 항목별 찬반·주의점과 대체 최소 기능을 검토했다.
- **결과:** 백엔드에서 제외할 기능과 추후 고도화 기능이 정리됐다.

### 요청 29 — F/C/M/D의 Spring·FastAPI 배치

- **사용자 요청:** 각 기능이 Spring인지 FastAPI인지 판단해 달라고 요청했다.
- **결론:** 저장·조회·오케스트레이션은 Spring, AI·규칙·RAG 계산은 FastAPI가 기본이었다.
- **수행 내용:** 기능별 주 담당과 보조 책임을 매핑했다.
- **결과:** 구현 충돌을 줄일 책임표가 생겼다.

### 요청 30 — 기능별 목표·책임·양쪽 구현 작성

- **사용자 요청:** F/C/M/D 각각에 목표, 주요 기능, 책임, FastAPI 기능과 Spring 기능을 작성해 달라고 요청했다.
- **결론:** 기능 명세를 동일한 템플릿으로 통일해야 했다.
- **수행 내용:** 각 기능을 구조화된 명세로 작성했다.
- **결과:** 팀 배분과 완료 판정에 사용할 수 있는 문서가 마련됐다.

### 요청 31 — ‘어떤 기능인가?’ 항목 추가

- **사용자 요청:** 각 기능에 쉬운 설명인 ‘어떤 기능인가?’를 추가해 달라고 요청했다.
- **결론:** 비개발자도 이해할 설명이 필요했다.
- **수행 내용:** 기술 명세 앞에 사용자 관점 설명을 추가했다.
- **결과:** 발표·Notion 공유에 적합한 문서가 됐다.

### 요청 32 — 뉴스 데이터 부재 시 C 기능 구현 여부

- **사용자 요청:** 뉴스 데이터가 전처리 중이라 없는 상황에서도 C 기능을 구현하는 것이 맞는지 물었다.
- **결론:** 입력 데이터와 독립적인 공통 기반은 먼저 구현하는 것이 맞았다.
- **수행 내용:** 파일 업로드·인증·오류·상태 관리 같은 C 기능의 선행 가치를 설명했다.
- **결과:** C1부터 구현하기로 했다.

### 요청 33 — C1 파일 업로드 구현

- **사용자 요청:** 지정된 Spring·FastAPI 폴더 형식을 지키며 C1을 구현해 달라고 요청했다.
- **결론:** Spring 외부 업로드와 FastAPI 내부 문서 처리로 나눠야 했다.
- **수행 내용:** Controller, DTO, Service, Config와 FastAPI API·Schema·Service를 구현했다.
- **결과:** C1 기본 업로드 흐름이 만들어졌다.

### 요청 34 — 생성 파일과 동작 설명

- **사용자 요청:** 어떤 폴더에 어떤 파일을 만들었고 무엇을 하는지 설명해 달라고 요청했다.
- **결론:** 파일 목록과 요청 흐름을 함께 설명해야 했다.
- **수행 내용:** 계층별 파일 목적을 정리했다.
- **결과:** 코드 리뷰와 팀 공유가 쉬워졌다.

### 요청 35 — C1 전용 코드와 공통 코드 표

- **사용자 요청:** C1을 위해 추가한 코드와 일반 구현에 필요한 코드를 한눈에 구분해 달라고 요청했다.
- **결론:** 기능 코드와 공통 인프라를 분리해 표시해야 했다.
- **수행 내용:** 파일·구분·목적 표를 작성했다.
- **결과:** 삭제하거나 재사용할 파일을 판단하기 쉬워졌다.

### 요청 36 — 파일이 너무 많은 이유

- **사용자 요청:** 하나의 기능에 파일을 너무 많이 만든 것 아니냐며 분리 이유를 물었다.
- **결론:** 계층 분리는 필요하지만 MVP 규모에서는 파일 수를 줄일 수 있었다.
- **수행 내용:** 책임 분리의 장단점과 통합 가능한 파일을 설명했다.
- **결과:** 기능별 폴더당 한 파일 원칙이 제안됐다.

### 요청 37 — 기능별 파일 최소화 적용

- **사용자 요청:** Spring과 FastAPI 모두 각 폴더에서 기능당 파일 하나 정도만 생성하도록 바꿔 달라고 요청했다.
- **결론:** 관련 DTO·예외·Service를 파일별 내부 타입으로 묶는 방식이 적절했다.
- **수행 내용:** C1 파일 구조를 축소·정리했다.
- **결과:** 이후 F1/F6와 Severity에도 같은 원칙을 적용했다.

### 요청 38 — C1 Spring·FastAPI 엔드포인트 확인

- **사용자 요청:** Spring `POST /api/v1/documents`와 FastAPI `POST /api/v1/documents/process`가 구현됐는지 확인했다.
- **결론:** 두 API의 존재와 호출 방향을 확인해야 했다.
- **수행 내용:** Controller·Router와 Service 연결을 점검했다.
- **결과:** 외부·내부 API 경계가 명확해졌다.

### 요청 39 — C1 요구사항 충족 분석

- **사용자 요청:** 파일 검증, Hash 중복, Metadata 저장, RAG 적재 등 C1 전체가 제대로 구현됐는지 분석해 달라고 요청했다.
- **결론:** 기본 업로드는 구현됐지만 PostgreSQL·ChromaDB·Embedding이 없어 완전한 C1은 아니었다.
- **수행 내용:** 요구사항별 완료율과 누락 의존성을 정리했다.
- **결과:** C1-A와 C1-B를 분리하기로 했다.

### 요청 40 — PostgreSQL·ChromaDB 우선순위

- **사용자 요청:** PostgreSQL, ChromaDB, RAG를 먼저 만들고 C1과 나머지 기능을 완성할지 물었다.
- **결론:** 영속 저장→문서 처리→VectorDB→검색 E2E 순서가 적절했다.
- **수행 내용:** 단계별 의존 관계를 설명했다.
- **결과:** 기반부터 완성하는 로드맵이 확정됐다.

### 요청 41 — 정의서로 제안 로드맵 구현 가능 여부

- **사용자 요청:** 기획·모델링 정의서와 To Do로 제안한 로드맵을 실제 구현할 수 있는지 물었다.
- **결론:** 가능하지만 설계 충돌과 Mock/실제 구분을 먼저 확정해야 했다.
- **수행 내용:** 문서 간 책임과 데이터 흐름을 대조했다.
- **결과:** 구현 가능한 통합 로드맵으로 정리됐다.

### 요청 42 — 두 로드맵 중 올바른 순서

- **사용자 요청:** PostgreSQL부터 실제 모델 교체까지 제시된 두 순서 중 무엇이 맞는지 물었다.
- **결론:** 설계 확정→최소 Schema→C1-A→Chroma/Mock→E2E→업무 기능 순서가 더 정확했다.
- **수행 내용:** 중복 단계를 합치고 C1-A/B를 분리했다.
- **결과:** 업그레이드된 로드맵의 뼈대가 만들어졌다.

### 요청 43 — C1 이후 React 검증 항목

- **사용자 요청:** C1 이후 React를 연결하면 무엇을 검증해야 하는지 물었다.
- **결론:** 파일 선택, 업로드 상태, 완료·실패·중복과 상태 조회를 검증해야 했다.
- **수행 내용:** 화면 상태와 API 오류 시나리오를 정리했다.
- **결과:** React 업로드 연결 완료 기준이 생겼다.

### 요청 44 — PostgreSQL 구현 방법

- **사용자 요청:** 첨부 제안에 따라 PostgreSQL을 어떻게 구현할지 물었다.
- **결론:** Docker, DataSource, Flyway, JPA, Health와 Migration 규칙이 필요했다.
- **수행 내용:** 설정·테이블·실행·검증 방법을 단계별로 설명했다.
- **결과:** PostgreSQL 기본환경 구축을 시작했다.

### 요청 45 — 김민지 역할과 팀원 역할 중복

- **사용자 요청:** 이미지의 역할 분담에서 자신의 구현 범위가 다른 사람과 겹치는지 물었다.
- **결론:** 공통 인프라는 김민지, 모델·RAG 세부 구현은 담당자에게 두고 인터페이스만 합의해야 했다.
- **수행 내용:** 역할별 소유 파일과 통합 지점을 설명했다.
- **결과:** 책임 충돌을 줄이는 협업 경계가 정해졌다.

### 요청 46 — C2와 PostgreSQL의 관계

- **사용자 요청:** 다른 팀원이 만드는 C2가 PostgreSQL 구현과 무관하므로 그대로 진행해도 되는지 물었다.
- **결론:** 기능은 병렬 진행 가능하지만 최종적으로 같은 PostgreSQL과 Migration 규칙을 사용해야 했다.
- **수행 내용:** DB 엔진, Migration 번호와 공통 파일 충돌 지점을 설명했다.
- **결과:** C2 담당자에게 전달할 협업 조건이 정리됐다.

### 요청 47 — C2 담당자에게 보낼 메시지 검토

- **사용자 요청:** PostgreSQL은 자신이 만들고 C2는 기존 설계를 진행한 뒤 SQL과 연동해 달라는 메시지가 적절한지 확인했다.
- **결론:** 내용은 적절하며 Migration·공통 설정·예외 파일 공유를 명시하는 것이 좋았다.
- **수행 내용:** 메시지 문구와 8개 협업 규칙을 다듬었다.
- **결과:** 팀원에게 전달 가능한 합의 문안이 마련됐다.

### 요청 48 — PostgreSQL 기본환경 로드맵 시작

- **사용자 요청:** C2 통합 이후 첨부 로드맵에 따라 PostgreSQL 기본환경부터 완벽한 데이터 구조를 설계할 수 있는지 물었다.
- **결론:** 최소 Schema부터 점진 확장하면 가능했다.
- **수행 내용:** Flyway, Master Data, 문서·ERP 테이블의 단계적 설계를 검토했다.
- **결과:** PostgreSQL 기반 구현이 시작됐다.

### 요청 49 — 실시간 관제 공통 Schema 반영

- **사용자 요청:** 조장이 제시한 `/api/v1/map/realtime-alerts` JSON이 현재 코드에 반영됐는지 확인하고 미반영이면 적용해 달라고 요청했다.
- **결론:** 지도·목록·AI 근거를 위한 별도 조회 계약으로 반영해야 했다.
- **수행 내용:** DTO·응답 구조와 `event_features` 매핑을 검토·반영했다.
- **결과:** 프론트 공통 관제 Schema가 백엔드 계약에 포함됐다.

### 요청 50 — 지금까지 추가한 SQL·Schema·C1 설명

- **사용자 요청:** SQL 설계, 조장 Schema 반영, C1 구현을 분류해 아주 쉽게 설명하고 계획 수정 여부와 추가 기능인지 판단해 달라고 요청했다.
- **결론:** 관제 Schema는 별도 AI 기능보다는 프론트 조회 계약의 확장에 가까웠다.
- **수행 내용:** DB, API DTO, C1 상태를 구분해 설명했다.
- **결과:** 계획 문서에서 수정할 지점과 현재 완료 범위를 이해했다.

### 요청 51 — v2 To Do와 Interface Specification 수정점

- **사용자 요청:** 새 방안을 적용하면 기존 두 문서에서 무엇을 수정해야 하는지 물었다.
- **결론:** 책임, 엔드포인트, `snake_case`, 관제 응답과 Mock 표시를 동기화해야 했다.
- **수행 내용:** 문서별 수정 목록을 제시했다.
- **결과:** 문서 간 API 불일치를 줄일 기준이 생겼다.

### 요청 52 — 더미 응답 대신 관제 응답 사용 여부

- **사용자 요청:** 단건 Risk 더미 응답이 아니라 조장이 제시한 `alerts[]` 응답을 따라야 하지 않느냐고 확인했다.
- **결론:** 해당 화면 API에서는 조장 Schema가 우선이었다.
- **수행 내용:** 두 응답의 용도를 구분하고 관제 API 계약을 우선 적용했다.
- **결과:** 프론트와 백엔드가 같은 JSON을 사용하게 됐다.

### 요청 53 — 팀 전체 `snake_case` 변경

- **사용자 요청:** 전체 팀 필드 규칙을 `snake_case`로 변경해야 한다고 했다.
- **결론:** Spring, FastAPI, React와 Chroma Metadata까지 일관되게 바꿔야 했다.
- **수행 내용:** Serializer, Schema와 문서 예시를 `snake_case` 기준으로 정리했다.
- **결과:** 이후 모든 신규 API에 `snake_case`를 적용했다.

### 요청 54 — PostgreSQL 연결 후 다음 작업

- **사용자 요청:** Health가 `UP`인 것을 확인한 뒤 PostgreSQL 검증이 끝났는지, 다음에 무엇을 할지 물었다.
- **결론:** 연결만 확인한 것이며 Migration, 테이블, 영속성·재시작 검증이 더 필요했다.
- **수행 내용:** 다음 검증과 C1-A 진행 순서를 설명했다.
- **결과:** Health와 기능 완료를 구분하게 됐다.

### 요청 55 — F6 최소 Master·문서 Schema 완료 분석

- **사용자 요청:** C1-A 전에 자재·공급사·계약·문서 Schema를 해야 하는지, 이미 완료됐는지 분석해 달라고 요청했다.
- **결론:** 최소 Schema는 일부 완료됐지만 ERP 외부 ID와 확장 데이터는 추가가 필요했다.
- **수행 내용:** Migration과 테이블·Constraint를 점검했다.
- **결과:** F6 구현 파일과 미완료 영역이 정리됐다.

### 요청 56 — Seed·Entity·Repository·Volume·상태 조회 진행

- **사용자 요청:** 최소 Seed, C1-A Entity/Repository, 원본 Volume, 상태 저장·조회와 재시작 영속성을 진행하자고 했고 먼저 F6 구현 파일을 물었다.
- **결론:** F6는 우선 Flyway SQL과 설정으로 구현돼 있었다.
- **수행 내용:** 관련 Migration·Docker·설정 파일을 설명하고 C1-A 영속화를 진행했다.
- **결과:** C1-A의 PostgreSQL 저장 기반이 완성됐다.

### 요청 57 — C1-A 처리 흐름 확인

- **사용자 요청:** 12단계 처리 흐름과 안전 항목, 완료 기준대로 진행하는 것이 맞는지 확인했다.
- **결론:** 제시된 흐름이 C1-A의 정확한 범위였다.
- **수행 내용:** 파일 저장과 DB 보상 처리 원칙을 재확인했다.
- **결과:** RAG 검색과 구분된 C1-A 완료 기준이 확정됐다.

### 요청 58 — C2 코드를 C1-A 프로젝트에 병합

- **사용자 요청:** C1-A 완료 상태에서 C2 담당자 코드를 어떻게 합칠지 물었다.
- **결론:** 공통 파일 충돌을 먼저 확인하고 PostgreSQL Migration과 Security 설정을 순서대로 병합해야 했다.
- **수행 내용:** 병합 순서와 검증 항목을 제안했다.
- **결과:** C1-A를 기준으로 C2 통합을 시작했다.

### 요청 59 — C2 Spring 코드 통합 구현

- **사용자 요청:** 나열한 domain, repository, dto/auth, exception, security, config, service, controller 파일을 C1-A 코드에 추가해 달라고 요청했다.
- **결론:** 기존 C1 기능을 바꾸지 않고 인증 기능을 추가해야 했다.
- **수행 내용:** Security/JWT/인증 API를 통합했다.
- **결과:** Spring에서 C1과 C2가 함께 동작하는 구조가 됐다.

### 요청 60 — C2 누락 공통 설정 확인

- **사용자 요청:** 공통 응답 DTO, Security/JWT 의존성·환경설정과 users Migration이 첨부 폴더에 있는지 물었다.
- **결론:** 코드 파일뿐 아니라 빌드·환경·Migration도 함께 확인해야 했다.
- **수행 내용:** 폴더와 설정 파일을 조사해 누락 여부를 정리했다.
- **결과:** C2 통합에 필요한 공통 요소를 보완했다.

### 요청 61 — 회원가입 역할 세 가지 확정

- **사용자 요청:** 구매팀, 경영기획팀, 경영진 세 역할을 사용한다고 확정했다.
- **결론:** Enum·검증·DB Constraint가 세 역할과 일치해야 했다.
- **수행 내용:** `PURCHASING`, `STRATEGY`, `EXECUTIVE`를 반영했다.
- **결과:** 회원가입 역할 계약이 확정됐다.

### 요청 62 — AuthFlowTest 네 시나리오 확인

- **사용자 요청:** 가입·로그인·로그아웃, Refresh, 잘못된 비밀번호, 공개/보호 API 테스트가 실제로 확인됐는지 물었다.
- **결론:** 테스트 이름이 아니라 assertion과 실행 결과를 확인해야 했다.
- **수행 내용:** 네 테스트의 흐름과 상태·오류 코드를 점검했다.
- **결과:** C2 핵심 인증 흐름의 동작을 확인했다.

### 요청 63 — C2 기능 임의 변경 여부 확인

- **사용자 요청:** 담당자가 구현한 기능을 임의로 바꾸지 않았는지 주의해서 확인해 달라고 요청했다.
- **결론:** 통합은 저장 방식과 공통 규칙만 맞추고 인증 의미를 유지해야 했다.
- **수행 내용:** 원래 시나리오와 통합 코드를 비교했다.
- **결과:** 기능 계약을 보존하는 원칙을 재확인했다.

### 요청 64 — C2 In-memory를 PostgreSQL로 전환

- **사용자 요청:** 새 파일 생성 여부와 C2를 In-memory에서 PostgreSQL 방식으로 바꾸고 이것으로 완전 연동인지 물었다.
- **결론:** User와 로그아웃 세션 모두 영속 Repository·Migration을 사용해야 했다.
- **수행 내용:** 저장 계층을 PostgreSQL로 연결하고 남은 검증을 설명했다.
- **결과:** C2가 PostgreSQL 기반으로 통합됐다.

### 요청 65 — C2 담당자에게 검증 요청 메시지

- **사용자 요청:** 통합 코드를 Push했으니 담당자에게 PostgreSQL 연동 검증을 부탁하는 메시지가 적절한지 물었다.
- **결론:** 브랜치, 확인 항목과 변경 파일 공유를 명확히 하면 적절했다.
- **수행 내용:** 전달 문구를 다듬었다.
- **결과:** 담당자가 수행할 검증 작업이 명확해졌다.

### 요청 66 — ERP CSV 사용 방법 분석

- **사용자 요청:** 팀원이 보낸 `spring-csv` ERP 파일을 어떻게 사용할지 분석해 달라고 요청했다.
- **결론:** Spring CSV는 PostgreSQL Seed, Agent CSV는 계산 검증 Fixture로 분리해야 했다.
- **수행 내용:** 파일·열·관계와 적재 순서를 분석했다.
- **결과:** ERP 데이터의 역할이 정리됐다.

### 요청 67 — 4단계와 ERP 중 우선순위

- **사용자 요청:** 4단계 FastAPI 추출·청킹과 ERP 중 무엇을 먼저 할지 물었다.
- **결론:** C1 의존 순서상 4단계를 진행하되 ERP는 병렬 준비할 수 있었다.
- **수행 내용:** 작업 충돌과 의존성을 비교했다.
- **결과:** 4단계를 우선 시작하기로 했다.

### 요청 68 — VectorDB 구현 시점과 위임

- **사용자 요청:** VectorDB를 언제 만들고 다른 팀원에게 맡겨도 되는지 물었다.
- **결론:** 4단계 청크 계약 확정 후 5단계로 위임하는 것이 적절했다.
- **수행 내용:** 전달해야 할 Interface와 소유 파일을 설명했다.
- **결과:** 태희님에게 ChromaDB를 맡기는 방향이 정해졌다.

### 요청 69 — Agent CSV 없이 4단계 시작

- **사용자 요청:** Agent CSV를 아직 못 받은 상태에서 Spring CSV를 기반으로 4단계를 시작해도 되는지 물었다.
- **결론:** 문서 추출·청킹은 ERP Fixture와 독립적이므로 시작할 수 있었다.
- **수행 내용:** 팀원에게 보낸 역할 분담 메시지도 검토했다.
- **결과:** 4단계를 진행했다.

### 요청 70 — 태희님 청킹·OpenAI 코드와 역할 중복

- **사용자 요청:** 태희님이 TextLoader, RecursiveCharacterTextSplitter와 OpenAIEmbeddings로 Chroma 저장 코드를 만들겠다고 했는데 4단계와 겹치는지, 무엇이라 답할지 물었다.
- **결론:** 청킹은 사용자 코드의 출력을 재사용하고 태희님 코드는 Vector 저장에 집중해야 했다.
- **수행 내용:** 청크 입력 계약과 Embedding DI를 요청하는 답변을 제안했다.
- **결과:** 중복 청킹을 피할 협업 메시지가 마련됐다.

### 요청 71 — 4단계 청크 출력 완성 확인

- **사용자 요청:** 4단계 청크 출력 형식이 완성됐는지 물었다.
- **결론:** ID, 페이지, 인덱스와 원본 Hash 보존 여부를 확인해야 했다.
- **수행 내용:** 실제 Schema와 Service 출력을 점검했다.
- **결과:** 4단계 출력 계약을 확인했다.

### 요청 72 — 9개 청크 필드 규격 확인

- **사용자 요청:** `document_id`부터 `content_hash`까지 9개 필드가 정확한 규격인지 물었다.
- **결론:** 4단계 도메인 청크의 표준 규격이 맞았다.
- **수행 내용:** 필드별 의미와 Chroma 저장 시 분리 방식을 설명했다.
- **결과:** 팀 간 청크 계약이 확정됐다.

### 요청 73 — 5단계를 맡긴 뒤 6단계 진행 여부

- **사용자 요청:** 태희님이 ChromaDB를 맡았으니 자신은 6단계를 하면 되는지 물었다.
- **결론:** Mock Embedding을 독립적으로 구현할 수 있었다.
- **수행 내용:** Provider Interface와 통합 지점을 정리했다.
- **결과:** 6-A를 병렬 진행하기로 했다.

### 요청 74 — Embedding DI 요청 메시지 검토

- **사용자 요청:** 태희님에게 `EMBEDDING_PROVIDER=mock|openai`, `embed_documents`, `embed_query` 규격과 Metadata 저장을 요청한 뒤 6단계를 시작해도 되는지 확인했다.
- **결론:** 통합 충돌을 줄이는 적절한 메시지였다.
- **수행 내용:** Collection 분리와 기본값 Mock 원칙을 추가 확인했다.
- **결과:** 6단계 구현 조건이 확정됐다.

### 요청 75 — 6단계 시작

- **사용자 요청:** Mock Embedding 구현을 시작하자고 요청했다.
- **결론:** 토큰 Hash 기반 결정적 Vector가 적합했다.
- **수행 내용:** 설정과 `MockEmbedding` Service, 단위 테스트를 구현했다.
- **결과:** 6-A Mock Embedding이 완료됐다.

### 요청 76 — ERP CSV의 7단계 사용 가능성 분석

- **사용자 요청:** `agent-csv`, `spring-csv`의 이상점과 7단계 진행 가능성을 물었다.
- **결론:** 일부 Fixture 차이를 주의하되 데이터 재생성 없이 사용할 수 있었다.
- **수행 내용:** 식별자·날짜·상태·관계와 원천/Fixture 역할을 검토했다.
- **결과:** 5단계 코드 수령 후 7단계에 사용할 수 있다고 판단했다.

### 요청 77 — ERP 데이터 재작성 요청 필요 여부

- **사용자 요청:** 데이터를 만든 팀원에게 다시 만들어 달라고 할 필요가 없는지 물었다.
- **결론:** 구조적으로 사용 가능하므로 전체 재작성은 필요 없었다.
- **수행 내용:** 수정이 필요한 특정 오류만 구분했다.
- **결과:** 원본을 유지하고 Fixture를 보정하는 방향이 됐다.

### 요청 78 — 수정된 발주 Context CSV 수령

- **사용자 요청:** 날짜가 1일 크게 생성된 오류를 수정한 `03_erp_purchase_order_context.csv`를 받았다고 알렸다.
- **결론:** 수정본을 검증 기준으로 사용하면 됐다.
- **수행 내용:** 오류 원인과 수정 내용의 타당성을 확인했다.
- **결과:** ETA 계산 Fixture가 정상화됐다.

### 요청 79 — 4단계 청크와 5단계 Metadata 차이

- **사용자 요청:** 두 단계의 Metadata 형식이 다른 이유를 물었다.
- **결론:** 4단계는 content를 포함한 도메인 객체이고 5단계 Metadata는 Chroma에 저장 가능한 스칼라만 담는 구조였다.
- **수행 내용:** `content`는 document, Vector는 embedding, 나머지는 metadata로 나누는 방식을 설명했다.
- **결과:** 두 형식이 충돌이 아닌 변환 관계임을 확인했다.

### 요청 80 — 4~6단계 문서 수정사항

- **사용자 요청:** 청킹·Chroma·Mock Embedding 문서에서 수정할 부분이 있는지 물었다.
- **결론:** `snake_case`, content_hash, Collection 분리, Metadata 타입과 선필터 순서를 명확히 해야 했다.
- **수행 내용:** 문서 수정 목록을 제시했다.
- **결과:** 통합에 사용할 규격이 정교해졌다.

### 요청 81 — 7~16단계 문서 수정 필요 여부

- **사용자 요청:** 이후 로드맵 문서에는 수정할 사항이 없는지 확인했다.
- **결론:** 큰 방향은 맞지만 상태·오류·Mock 표시와 책임 문구를 일관되게 해야 했다.
- **수행 내용:** 후속 단계의 보완점을 검토했다.
- **결과:** 전체 로드맵의 일관성을 확보했다.

### 요청 82 — 수정 반영 전체 문서 출력

- **사용자 요청:** 원본 로드맵에 수정사항을 반영한 전체 문서를 출력해 달라고 요청했다.
- **결론:** 부분 패치가 아닌 복사 가능한 완성본이 필요했다.
- **수행 내용:** 단계별 전체 문서를 재구성했다.
- **결과:** 팀 Notion·Markdown에 사용할 통합 로드맵을 확보했다.

### 요청 83 — Chroma 코드 대기 중 구현할 기능

- **사용자 요청:** 태희님의 5단계 코드를 오래 기다리는 동안 F/C/M/D 중 무엇을 구현할지 물었다.
- **결론:** Chroma/RAG와 겹치지 않는 Spring F6·F1이 가장 효율적이었다.
- **수행 내용:** ERP Master 확장과 Context 계산을 추천했다.
- **결과:** F6/F1 Spring 영역을 진행하기로 했다.

### 요청 84 — F1 전체 구현 가능 여부

- **사용자 요청:** F1을 전부 구현할 수 있는지 물었다.
- **결론:** 결정적 수치와 Mock ERP는 가능하지만 실제 LLM 설명·실제 ERP·없는 가격 데이터는 보류해야 했다.
- **수행 내용:** 구현 가능·불가능 범위를 나눴다.
- **결과:** F1 Spring 부분부터 구현하기로 했다.

### 요청 85 — F1/F6 부분 구현과 전체 기능 완료율

- **사용자 요청:** F1+F6 Spring 영역만 구현하면 일부 완료인지, 지금까지 F~D 기능의 완료율을 정리해 달라고 요청했다.
- **결론:** 기능별 전체 완료와 담당 영역 완료를 구분해야 했다.
- **수행 내용:** 구현 목록과 예상 퍼센트를 분석했다.
- **결과:** 다음 작업이 F6 외부 ID와 F1 계산으로 좁혀졌다.

### 요청 86 — F6 외부 ID·F1 계산 구현

- **사용자 요청:** 기능당 폴더별 파일 하나 원칙으로 F6 ERP Master Schema·외부 ID 매핑과 F1 Spring ERP 조회·결정적 계산을 구현해 달라고 요청했다.
- **결론:** V4 Migration, CSV Seed, DTO·Repository·Service·Controller가 필요했다.
- **수행 내용:** 10개 ERP 테이블·확장, 523행 Seed, `/api/v1/erp/context`와 테스트를 구현했다.
- **결과:** FastAPI 51개 이전 시점의 Spring 전체 15개 테스트와 실제 PostgreSQL·HTTP 검증을 통과했다.

### 요청 87 — 태희님 수정본 대기 중 다음 작업

- **사용자 요청:** 태희님이 5단계 ChromaDB 코드를 다시 수정해 보내는 동안 추가 기능을 구현할지 물었다.
- **결론:** Chroma와 독립적인 11단계 Severity Rule Engine이 적절했다.
- **수행 내용:** 현재 변경을 커밋으로 분리하고 Severity를 진행하는 순서를 제안했다.
- **결과:** 11단계를 다음 작업으로 선택했다.

### 요청 88 — 11단계 Severity Rule Engine 구현

- **사용자 요청:** 첨부 로드맵에 따라 11단계를 구현해 달라고 요청했다.
- **결론:** FastAPI 계산뿐 아니라 Spring ERP Context 조립, 내부 호출, PostgreSQL 저장·조회까지 구현해야 했다.
- **수행 내용:** 9개 입력, 4등급, `UNKNOWN`, FEOC Hard Gate, 계산 상세, Spring API와 V5 Migration을 구현했다.
- **결과:** FastAPI 51개, Spring 19개 테스트와 일반/FEOC 실제 E2E를 통과했다.

### 요청 89 — 대화 Markdown 저장

- **사용자 요청:** 지금까지의 대화를 Markdown 파일로 저장해 달라고 요청했다.
- **결론:** 축어록보다 설계·구현 이력을 중심으로 한 협업 기록을 우선 작성했다.
- **수행 내용:** `docs/codex-conversation-history-2026-07-22.md`를 생성하고 당시 상태와 다른 기존 문구를 교정했다.
- **결과:** 주제별 요약 문서가 만들어졌다.

### 요청 90 — 빠진 내용의 이유 확인

- **사용자 요청:** 저장된 파일에 내용이 빠진 것 같은 이유를 물었다.
- **결론:** 첫 문서는 전체 요청별 기록이 아니라 주요 결정 요약본이었기 때문이다.
- **수행 내용:** 반복 대화 통합, 과거 답변 전문·첨부 원문 미보존과 요약 기준을 설명했다.
- **결과:** 확인 가능한 모든 요청을 별도로 확장하기로 했다.

### 요청 91 — 모든 사용자 요청을 상세 기록으로 확장

- **사용자 요청:** 확인 가능한 모든 사용자 요청을 순서대로 넣고 각 요청 아래에 `결론 / 수행 내용 / 결과`를 붙여 달라고 요청했다.
- **결론:** 기존 주제별 기록은 유지하고 요청별 부록을 추가하는 것이 가장 읽기 좋았다.
- **수행 내용:** 현재 확인 가능한 요청 1~91을 시간순으로 재구성했다.
- **결과:** 이 요청별 상세 기록이 문서에 추가됐다.
