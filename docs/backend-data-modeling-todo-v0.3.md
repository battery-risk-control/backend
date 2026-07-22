---

# 📌 데이터 / 모델링 / 백엔드 To Do List v0.3

## 프로젝트 기본 구조

```
React
  ↓
Spring Boot
  ├─ Dashboard / Risk / Contract / Briefing 조회 API
  ├─ ERP·계약 업무 데이터 관리
  ├─ 사용자·스케줄러 분석 요청 오케스트레이션
  ├─ FastAPI 분석 호출 및 결과 검증
  └─ PostgreSQL 전체 업무·이벤트·AI 결과 읽기·쓰기

사용자 요청 기반 분석

React
  ↓
Spring Boot
  ↓
FastAPI /api/v1/analyze

자동 데이터 수집 및 분석

MVP:
Spring Scheduler
  ↓
Spring 분석 Orchestration Service
  ↓
FastAPI /api/v1/analyze

고도화:
별도 Worker / Message Queue
  ↓
Spring 내부 분석 접수 API
  ↓
FastAPI /api/v1/analyze

FastAPI 분석 Service
  ├─ 뉴스 이벤트 분석
  ├─ LLM 정보 추출
  ├─ 피처 생성
  ├─ XGBoost Impact Domain 분류
  ├─ Severity Rule Engine
  ├─ Spring이 전달한 ERP Context 활용
  ├─ 계약서 RAG 검색
  ├─ 브리핑 생성
  └─ 분석 결과를 Spring Boot에 반환

FastAPI 데이터 접근
  ├─ PostgreSQL 직접 접근 없음
  └─ ChromaDB 계약서 임베딩 저장·검색
```

Spring Boot는 기업 업무 시스템과 API의 안정적인 중심을 맡고, FastAPI는 AI·ML·RAG 처리에 집중하도록 분리하는 구조이다.

#### Spring Boot가 맡는 역할

```python
사용자 요청 처리
프론트엔드 API 제공
계약·재고·공급사 조회
업무 데이터 저장·수정
트랜잭션 관리
공통 응답·예외 처리
권한과 인증
FastAPI 호출 및 결과 전달
분석 상태 및 결과 PostgreSQL 저장
```

주로 담당하는 데이터:

```python
materials
suppliers
supplier_materials
inventories
purchase_orders
contracts
contract_clauses
contract_documents
risk_events
risk_event_materials
event_extractions
event_labels
event_features
analysis_results
severity_results
briefings
briefing_references
```

#### FastAPI가 맡는 역할

```python
뉴스 이벤트 분석
LLM 정보 추출
피처 생성
XGBoost Impact Domain 분류
규칙 기반 Severity 계산
Spring이 전달한 ERP Context 활용
계약서 RAG 검색
브리핑 생성
AI 분석 과정 오케스트레이션
분석 결과 반환
```

직접 관리하는 영속 데이터:

```python
ChromaDB 벡터 데이터
```

FastAPI는 PostgreSQL 테이블의 소유권을 갖지 않으며, 분석 요청에 필요한 ERP·계약 Context는 Spring Boot가 DTO로 전달한다.

---

# 🎯 업무 기능 정의

기술 우선순위와 별개로 시스템이 제공해야 하는 업무 기능을 정의한다. 각 기능은 이후 0~15순위 구현 단계와 연결한다.

## F1. ERP 영향 분석 AI 에이전트

### 목표

감지된 리스크를 자재·공급사·재고·발주·계약 데이터와 연결하여 실제 구매 업무에 미치는 영향을 설명한다.

### 주요 기능

- 이벤트와 자재·공급사 매칭
- 현재 재고와 안전재고 비교
- 재고 소진 일수 계산
- 공급사 의존도 계산
- 발주·입고 예정일 확인
- 계약 단가와 가격 조정 임계치 비교
- 자재별 영향도와 근거 생성
- ERP Context Snapshot 보존

### 책임

- Spring Boot: ERP 조회, Context 구성, FastAPI 호출, 결과 저장·조회
- FastAPI: 이벤트·ERP Context 결합 분석, 근거와 권장 조치 반환

## F2. RAG 기반 계약·대체 공급망 분석

### 대상 문서

- 장기공급계약서 및 요약본
- 구매 가이드라인
- 공급사 평가 기준
- 품질 인증서
- 규제 문서
- 소재 기술 스펙

### 주요 기능

- PDF/TXT 업로드
- 조항 단위 청킹
- ChromaDB Embedding
- 계약·공급사·자재 Metadata Filter
- 원문·페이지·조항 근거 반환
- 계약 대응 방안 생성
- 적격 대체 공급사 후보 제시

### 책임

- Spring Boot: 파일 접수·검증, 메타데이터와 처리 상태 저장, FastAPI 호출
- FastAPI: 문서 처리, ChromaDB 저장·검색, 근거 기반 대안 생성

## F3. AI 기반 공급망 리스크 분석

### 주요 기능

- LLM 뉴스 정보 추출
- Feature 생성
- XGBoost Impact Domain 분류
- 규칙 기반 Severity 계산
- 판단 근거·신뢰도·버전 반환
- Mock/실제 분석 결과 구분
- 실패·재시도·재분석

### 역할 고정

```text
XGBoost → PRODUCTION / LOGISTICS / POLICY / MARKET / GEOPOLITICS 분류
Severity Rule Engine → NORMAL / WARNING / CRITICAL 계산
```

## F4. 멀티스피드 외부 데이터 모니터링

### Fast Track

- GDELT DOC API
- GDACS
- 기상·재난
- 광산 기업 주가·환율
- AIS 및 당일 ERP 증분
- 실행 주기: 15분~4시간

### Slow Track

- 공식 원자재 종가
- ERP Full Sync
- 관세청 수입 물량
- Google News RSS 보완 수집
- 실행 주기: 1일~1개월

### 주요 기능

- 수집 주기·활성 상태 관리
- Full/Incremental Sync
- Cursor와 마지막 성공 시각
- 중복 이벤트 제거
- 부분 성공·재시도
- 데이터 신선도 경고

## F5. 구매 브리핑·대시보드·보고서

- 재고 관점과 계약 관점 분리
- 자재·공급사 영향 요약
- 대체 공급사 비교표
- 예상 원가 영향 그래프용 데이터
- 협상 포인트와 권장 조치
- 근거 문서 참조
- 보고서 버전 관리
- PDF/Excel 다운로드

## F6. 개체 식별·Master Data 정규화

- 자재·공급사 Alias 사전
- 국가·항만 코드 표준화
- 사업자등록번호 우선 매칭
- 명칭 유사도 후보 매칭
- 자동 매칭 confidence
- 사람 승인·거절
- 병합 이력과 미매칭 데이터 관리

## F7. 출처·근거·데이터 계보

- 원본 URL·수집 시각·기준 시각
- 원본 Hash와 파싱 버전
- Feature·모델·규칙 버전
- ERP Context Snapshot
- RAG 문서·페이지·조항
- `CONFIRMED / REFERENCE / WARNING` 근거 구분
- 요청 전체 Trace ID

## F8. 규제 Hard Gate·정책 유효성

- FEOC·CRMA 규제 판정
- 관할 지역과 적용 시장
- 효력 시작일·종료일
- 현재 유효 여부
- 정책 문서 버전
- 규제 위반 시 CRITICAL 강제 승격
- 만료·폐지된 규칙의 자동 제외

## F9. 공급사 자격·대체 공급사 추천

- IATF 16949와 PPAP
- FEOC·규제 적합성
- 배터리급 자재 공급 가능 여부
- 생산 능력·MOQ·리드타임
- 가격·품질·과거 리스크
- 적격 후보 필터와 추천 근거
- 최종 결정은 사용자가 수행

## F10. 알림·에스컬레이션

- Severity별 알림 정책
- 관심 자재·공급사 구독
- 이메일·Slack·Teams 연동
- 중복 알림 억제
- 확인·미확인 상태
- 미확인 Critical 자동 에스컬레이션
- Quiet Hours와 발송 이력

## F11. 사용자 계층별 대시보드

- 구매팀: 재고·계약·공급사·실행 조치
- 경영기획: 사업부·공급사·지역별 노출과 추이
- 경영진: 핵심 KPI, Critical 리스크, 재무 영향, 대응 상태
- 실시간 지도 마커·알림 목록·AI Feature 근거 조회
- Spring 공개 API `GET /api/v1/map/realtime-alerts`
- 역할: `BUYER / PLANNING / EXECUTIVE / ADMIN`

## F12. 대응 조치·업무 추적

- 권장 조치를 Task로 전환
- 담당자·기한·우선순위
- 진행 상태·댓글·첨부파일
- 승인과 완료
- 리스크 종료 및 대응 이력

---

# 🧩 공통 기반 기능

## C1. 파일 업로드

- Multipart 업로드
- 확장자·MIME·크기 검증
- SHA-256 Hash와 중복 방지
- 로컬/Docker Volume, 이후 S3 전환
- 업로드 사용자·처리 상태·재처리
- 다운로드·삭제 권한

## C2. 인증·권한

- 회원가입·로그인·로그아웃
- BCrypt
- JWT Access/Refresh Token
- 역할 기반 접근 제어
- 관리자 승인 정책
- 파일·분석·보고서 권한

## C3. 분석 작업·재시도

- `PENDING / PROCESSING / COMPLETED / FAILED`
- 시작·종료 시각
- 재시도 횟수
- 오류 코드·메시지
- 요청 사용자와 Mock 여부
- 모델·규칙 버전

## C4. 감사 로그

- 로그인·권한 변경
- 문서 업로드·삭제
- 분석·재분석
- 브리핑 재생성
- 수집 작업 수동 실행
- 보고서 다운로드

## C5. Idempotency

- `Idempotency-Key`
- `external_event_id`
- 문서 Hash
- 동일 요청의 중복 저장·중복 실행 방지

## C6. 관측성

- Spring/FastAPI/PostgreSQL/ChromaDB Health
- 외부 API와 모델 상태
- 구조화 로그와 Trace ID
- 분석 단계별 처리 시간
- 오류율·Fallback 사용률

## C7. LLM 안전장치

- Prompt Version
- 입력 길이와 민감정보 제한
- Prompt Injection 방어
- 출력 JSON Schema 검증
- 근거 없는 추천 차단
- 단정 표현 제한
- Token·비용·timeout 기록

---

# 🤖 AI·데이터 품질 기능

## M1. 사람 검증 라벨링

- LLM 초안 라벨과 최종 라벨 분리
- Blind Review
- 불일치·재검토
- Cohen's Kappa
- 라벨 버전·변경 이력

## M2. Dataset·Feature 버전 관리

- Dataset Manifest
- 수집 기간·소스·Seed
- Feature Schema
- 클래스 분포
- 시계열 Train/Validation/Test 구간
- Checksum과 생성 스크립트 버전

## M3. Model Registry

- 모델·학습 데이터 버전
- 평가 지표와 승인 상태
- Production 모델 지정
- Artifact Checksum
- 배포·Rollback·폐기

## M4. 시계열 학습 검증

- Random Shuffle 금지
- Look-ahead Bias 방지
- 동일 기사·사건 Cluster 분리 방지
- 기간별 Precision/Recall/F1
- Confusion Matrix

## M5. 설명 가능성

- SHAP 상위 Feature
- 기여 방향·분류 Confidence
- Severity 계산 근거
- LLM 설명과 SHAP 방향 일치 검증

## M6. 모델·데이터 모니터링

- 입력·클래스 분포 변화
- 결측치·오류율 증가
- Confidence 하락
- 국가·언어·뉴스 소스 편향
- 모델 상태 `HEALTHY / WARNING / DEGRADED / UNAVAILABLE`

## D1. 중복 뉴스·사건 군집화

- URL 정규화
- 제목·본문 Hash/유사도
- GDELT Event ID
- 사건 Cluster와 대표 기사
- 보도량 집계

## D2. 소스 신뢰도

- `OFFICIAL / VERIFIED_MEDIA / INDUSTRY_SOURCE / UNVERIFIED`
- Severity 보조 근거
- RAG 우선순위
- 브리핑 인용 가능 여부

## D3. 데이터 품질 검증

- 국가·자재·공급사 식별 검증
- 음수 재고·잘못된 계약 기간
- 가격·통화·단위 이상값
- 중복 이벤트 ID
- 오래된 ERP Snapshot
- `VALID / WARNING / INVALID / QUARANTINED`

## D4. 단위·통화 표준화

- 통화 환산과 환율 기준 시각
- TON/KG 변환
- 광물 가격 단위
- 배터리급 Grade
- 현물·선물·기준 시장 구분

---

# ✅ 0순위. API 계약 및 서비스 책임 확정

## 목표

React, Spring Boot, FastAPI가 동일한 서비스 경계와 데이터 규격을 기준으로 병렬 개발할 수 있도록 Interface Specification v0.2를 확정한다.

배터리 원자재 공급망 리스크 관제 시스템 Interface Specification v0.2

## 서비스 책임

### React

- Spring Boot API만 직접 호출
- FastAPI를 직접 호출하지 않음
- 대시보드, 리스크 상세, 계약, 브리핑 화면 담당

### Spring Boot

- 프론트엔드용 외부 API
- ERP 및 계약 정형 데이터 관리
- 대시보드 통계
- 리스크 및 브리핑 결과 조회
- 사용자·스케줄러 분석 요청 오케스트레이션
- ERP·계약 Context 구성 및 FastAPI 전달
- FastAPI 응답 검증과 분석 상태 관리
- PostgreSQL 전체 테이블의 단일 읽기·쓰기 주체

### FastAPI

- 외부 데이터 수집 및 피처 보강
- LLM 뉴스 정보 추출
- XGBoost Impact Domain 분류
- 규칙 기반 Severity 계산
- RAG 검색
- 브리핑 생성
- Spring이 전달한 ERP·계약 Context 활용
- 분석 결과를 Spring에 반환
- ChromaDB 벡터 데이터 읽기·쓰기
- PostgreSQL 직접 접근 금지

## FastAPI 호출 원칙

- React는 FastAPI를 직접 호출하지 않는다.
- 사용자 요청 분석은 Spring Boot가 접수·상태 관리하고 FastAPI의 `/api/v1/analyze`를 호출한다.
- 자동 분석은 MVP에서 Spring Scheduler가 동일한 Orchestration Service를 호출한다.
- 고도화 단계의 별도 Worker는 Spring 내부 분석 접수 API 또는 메시지 큐를 통해 작업을 전달한다.
- FastAPI는 분석 결과를 PostgreSQL에 직접 저장하지 않는다.
- Spring Boot는 FastAPI 성공 응답을 검증한 뒤 하나의 트랜잭션으로 PostgreSQL에 저장한다.
- 내부 단계 API는 개발·테스트 목적으로만 사용하며 외부 프론트엔드에 노출하지 않는다.

## 상세 작업

- REST API 목록 확정
- Request / Response JSON 구조 정의
- HTTP Status Code 정의
- 공통 Error Response 정의
- Enum 정의
- 서비스별 데이터 소유권 정의
- Spring–FastAPI 내부 통신 모델 정의
- OpenAPI 및 Swagger 문서 확인

## Spring Boot 외부 API

```
GET /api/v1/dashboard/summary
GET /api/v1/risks
GET /api/v1/risks/{risk_id}
GET /api/v1/contracts
GET /api/v1/contracts/{contract_id}
GET /api/v1/risks/{risk_id}/briefing
POST /api/v1/analyses
GET /api/v1/analyses/{analysis_id}
GET /api/v1/map/realtime-alerts
```

## FastAPI 핵심 API

```
POST /api/v1/analyze
POST /api/v1/rag/contracts
POST /api/v1/rag/search
```

## FastAPI 개발·테스트용 내부 API

```
POST /api/v1/internal/llm/extract
POST /api/v1/internal/ml/classify
POST /api/v1/internal/severity/score
POST /api/v1/internal/briefings
```

## 완료 기준

- Interface Specification v0.2 작성
- API 경로 및 Enum 팀 합의
- PostgreSQL 단일 쓰기 책임을 Spring Boot로 확정
- ChromaDB 쓰기 책임을 FastAPI로 확정
- 프론트엔드 담당자에게 Response JSON 공유
- 이후 변경 사항은 `CHANGELOG.md`에 기록

---

# ✅ 1순위. Spring Boot Dummy API 구축 및 공유

## 목표

프론트엔드가 실제 DB와 AI 모델이 없어도 대시보드 및 상세 화면 개발을 시작할 수 있도록 실제 명세와 동일한 Dummy API를 제공한다.

## 상세 작업

- Spring Boot 프로젝트 생성
- Controller / Service / DTO 구조 생성
- 공통 응답 객체 구현
- 공통 예외 응답 구현
- 더미 데이터 반환 구현
- CORS 설정
- Swagger 또는 Springdoc 설정
- Postman 테스트

## 구현 API

```
GET /api/v1/dashboard/summary
GET /api/v1/risks
GET /api/v1/risks/{risk_id}
GET /api/v1/contracts
GET /api/v1/contracts/{contract_id}
GET /api/v1/risks/{risk_id}/briefing
GET /api/v1/map/realtime-alerts
```

## 더미 응답 원칙

단순한 임시 JSON이 아니라 실제 DTO 구조와 동일한 응답을 반환한다.

```
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

## 완료 기준

- 7개 API HTTP 200 자동 테스트 통과
- 잘못된 리스크·계약·브리핑 ID의 404 테스트 통과
- 잘못된 Enum 및 요청 파라미터의 400 테스트 통과
- React 개발 환경에서 최소 1개 API 실제 호출 성공
- Swagger UI에서 전체 Endpoint와 Schema 확인
- Swagger 주소와 API 명세를 팀에 공유
- Postman Collection은 협업용 선택 산출물

- 확인 목록
    
    !image.png
    
    위 사진의 각 패키지 안에 Controller, Service, Response DTO로 구성되어 있음
    
    - Response DTO란?
        
        Response DTO는 프론트엔드에 어떤 JSON을 내려줄지 자바 코드로 명확하게 정의한 객체이다.
        
    
    http://localhost:8080/swagger-ui/index.html#/
    
    에서, 
    
    !image.png
    
    로 나오는 것을 확인.
    
    - 정상 응답과 오류 응답 확인
    
    GET /api/v1/risks/101 ⇒ 
    
    HTTP 200
    success: true
    
    로 나오는 것을 확인.
    
    GET /api/v1/risks/501 ⇒
    
    HTTP 404
    success: false
    code: **`RISK_NOT_FOUND`**
    
    로 나오는 것을 확인.
    

---

# ✅ 2순위. FastAPI AI Mock 서버 구축 및 Swagger 공개

## 목표

AI·ML·RAG 로직을 담을 FastAPI 서버의 구조를 생성하고, 실제 모델이 없는 상태에서도 전체 분석 인터페이스를 테스트할 수 있도록 한다.

## 범위

본 단계는 AI 파이프라인의 API 계약, 오케스트레이션 구조 및 Mock 구현을 완료하는 단계이다.

다음은 후속 단계에서 교체한다.

- 실제 LLM
- 실제 XGBoost Artifact
- 실제 PostgreSQL Repository
- 실제 ChromaDB
- 실제 Embedding Model
- 실제 PDF 텍스트 추출

## 현재 프로젝트 구조

```
app/
├─ main.py
├─ core/
│  ├─ config.py
│  └─ exceptions.py
├─ api/
│  ├─ dependencies.py
│  └─ v1/
│     ├─ analyze.py
│     ├─ rag.py
│     └─ internal.py
├─ schemas/
│  ├─ common.py
│  ├─ analyze.py
│  ├─ extraction.py
│  ├─ classification.py
│  ├─ severity.py
│  ├─ rag.py
│  └─ briefing.py
├─ services/
│  ├─ orchestration_service.py
│  ├─ extraction_service.py
│  ├─ classification_service.py
│  ├─ severity_service.py
│  ├─ context_service.py
│  ├─ rag_service.py
│  └─ briefing_service.py
├─ ml/
│  ├─ inference.py
│  ├─ feature_builder.py
│  └─ artifacts/
├─ rag/
│  ├─ loader.py
│  ├─ chunker.py
│  ├─ embeddings.py
│  └─ vector_store.py
└─ utils/
```

FastAPI에는 PostgreSQL repository 계층을 두지 않는다. `context_service.py`는 Spring이 요청 DTO로 전달한 ERP·계약 Context의 검증과 분석용 변환만 담당한다.

## 핵심 API

```
POST /api/v1/analyze
POST /api/v1/rag/contracts
POST /api/v1/rag/search
```

## 내부 API

```
POST /api/v1/internal/llm/extract
POST /api/v1/internal/ml/classify
POST /api/v1/internal/severity/score
POST /api/v1/internal/briefings
```

## 구현 원칙

- Spring Boot가 시스템 전체 분석 흐름과 저장을 오케스트레이션
- FastAPI `/analyze`는 전달받은 Event·ERP Context를 대상으로 AI 파이프라인만 오케스트레이션
- 내부 단계 API는 Swagger 테스트용
- React는 내부 API를 호출하지 않음
- FastAPI는 PostgreSQL을 직접 읽거나 쓰지 않음
- FastAPI 응답은 Spring이 저장할 수 있는 완결된 분석 결과 DTO를 사용
- 실제 모델이 없을 경우 동일 스키마의 Mock 응답 사용
- 모든 JSON은 `snake_case`
- Pydantic 내부 Python 필드는 `snake_case` 사용 가능
- Pydantic 필드명을 그대로 `snake_case`로 직렬화

## 완료 기준

- `http://localhost:8000/docs` 정상 동작
- 모든 API에 Request / Response Schema 표시
- `/analyze` Mock End-to-End 응답 확인
- Swagger URL 또는 화면 공유
- ERP Context 포함 `/analyze` Mock 요청·응답 확인
- FastAPI 코드에 PostgreSQL 쓰기 의존성이 없음을 확인

- 확인 목록
    
    !image.png
    

---

# ✅ 2.5순위. Spring Boot–FastAPI 분석 연동

## 목표

Spring Boot를 전체 오케스트레이터로 두고, 사용자 또는 Scheduler의 분석 요청을 FastAPI에 전달한 뒤 결과를 검증하여 PostgreSQL에 저장할 수 있는 경계를 구현한다.

## Spring Boot 외부 API

```text
POST /api/v1/analyses
GET /api/v1/analyses/{analysis_id}
```

## Spring → FastAPI 내부 호출

```text
POST http://{fastapi-host}:8000/api/v1/analyze
```

요청에는 다음을 포함한다.

- Event 원문과 출처
- Spring이 조회한 자재·공급사·재고·발주 Context
- 계약 ID 및 RAG 검색 범위
- Feature Override
- 실행 Option

## 처리 흐름

```text
React 또는 Spring Scheduler
→ Spring 분석 상태 PENDING 저장
→ Spring ERP·계약 Context 조회
→ FastAPI /analyze 호출
→ FastAPI 분석 결과 반환
→ Spring 응답 검증
→ Spring 분석·Severity·Briefing 결과 트랜잭션 저장
→ COMPLETED 또는 FAILED 상태 갱신
```

## 완료 기준

- Spring–FastAPI 통신 DTO 1:1 일치
- 정상 요청에서 FastAPI Mock 결과 수신
- Spring이 수신 결과를 저장 가능한 형태로 변환
- timeout 및 FastAPI 4xx/5xx/연결 실패 처리
- `PENDING / PROCESSING / COMPLETED / FAILED` 상태 전이 테스트
- React는 FastAPI가 아니라 Spring 분석 API만 호출

---

# ✅ 3순위. PostgreSQL DB 설계 및 ERD 작성

## 목표

ERP, 계약, 외부 이벤트, AI 분석 결과, 브리핑 데이터를 저장할 관계형 데이터베이스 구조를 설계하고,
Spring Boot가 단일 읽기·쓰기 주체가 되는 PostgreSQL 기반을 구축한다.

## MVP DB 선택

MVP에서는 PostgreSQL 하나를 프로젝트 공통 RDB로 사용한다.

실제 ERP MySQL/MariaDB 연동은 고도화 단계에서 별도로 추가한다.

실제 ERP MySQL
↓ ERP Sync
프로젝트 PostgreSQL

## 핵심 테이블

### 기준 정보

- `materials`
- `suppliers`
- `supplier_materials`

### ERP 데이터

- `inventories`
- `purchase_orders`

### 계약 데이터

- `contracts`
- `contract_clauses`
- `contract_documents`

### 외부 이벤트 및 AI 결과

- `risk_events`
- `risk_event_materials`
- `event_extractions`
- `event_labels`
- `event_features`
- `analysis_results`
- `severity_results`
- `briefings`
- `briefing_references`

## 이벤트·라벨 데이터 구분

### risk_events

외부 뉴스 및 이벤트의 원문과 출처 정보를 저장한다.

주요 데이터:

- 제목
- 본문
- 출처
- 원문 URL
- 국가
- 발생일
- 탐지일
- 외부 이벤트 식별자

### risk_event_materials

하나의 이벤트가 여러 자재에 영향을 줄 수 있으므로
`risk_events`와 `materials`의 N:M 관계를 관리한다.

주요 데이터:

- `risk_event_id`
- `material_id`
- `match_type`
- `confidence_score`

### event_extractions

LLM이 뉴스 원문에서 추출한 구조화 정보를 저장한다.

주요 데이터:

- `country`
- `event_type`
- `tone_score`
- `impact_domain_draft`
- `summary_kr`
- `extraction_model_version`

### event_labels

LLM 초안 라벨과 사람 검증 결과를 분리하여 저장한다.

주요 데이터:

- `impact_domain_final`
- `label_source`
- `review_status`
- `reviewer_count`
- `reviewed_at`

### event_features

XGBoost 학습 및 추론에 사용하는 최종 피처를 저장한다.

주요 데이터:

- `tone_score`
- `goldstein_scale`
- `news_count`
- `country_is_mining_hub`
- `rainfall_24h_mm`
- `gdacs_alert_level`
- `actor1_type`
- `actor2_type`
- `stock_volatility_20d`

## 쓰기 책임

| 데이터 영역 | 주 쓰기 담당 | 주요 읽기 담당 |
| --- | --- | --- |
| 자재·공급사 | Spring Boot | Spring Boot |
| 재고·발주 | Spring Boot | Spring Boot |
| 계약 메타데이터·검증된 조항 | Spring Boot | Spring Boot |
| 외부 이벤트 원문 | Spring Boot | Spring Boot |
| 이벤트-자재 매핑 | Spring Boot | Spring Boot |
| LLM 추출 결과 | Spring Boot | Spring Boot |
| 사람 검증 라벨 | Spring Boot 또는 Spring 기반 검수 API | Spring Boot |
| 최종 피처 | Spring Boot | Spring Boot |
| Impact Domain 분류 결과 | Spring Boot | Spring Boot |
| Severity 결과 | Spring Boot | Spring Boot |
| 브리핑 | Spring Boot | Spring Boot |
| 벡터 데이터 | FastAPI | FastAPI |

## DB 관리 원칙

- 공통 PostgreSQL Schema Migration은 Spring Boot의 Flyway만 담당한다.
- PostgreSQL의 모든 테이블 읽기·쓰기와 트랜잭션은 Spring Boot가 담당한다.
- FastAPI는 PostgreSQL 드라이버·ORM·Migration 도구를 사용하지 않는다.
- FastAPI에 필요한 ERP·계약 데이터는 Spring Boot가 내부 통신 DTO로 전달한다.
- FastAPI 분석 결과는 Spring Boot가 검증한 뒤 관련 테이블에 저장한다.
- FastAPI는 ChromaDB 컬렉션과 벡터 Metadata만 직접 관리한다.
- 기존 Flyway Migration 파일은 적용 후 수정하지 않고 신규 버전을 추가한다.

예:

V1__create_initial_schema.sql
V2__add_risk_indexes.sql
V3__alter_contract_documents.sql

## PostgreSQL 계정 권한

### spring_app

- 전체 PostgreSQL 업무·이벤트·AI 결과 테이블: READ / WRITE

### fastapi_app

- PostgreSQL 계정 발급하지 않음
- ChromaDB 및 AI 외부 서비스 접근 권한만 부여

## 설계 내용

- PK / FK 정의
- 1:N, N:M 관계 정의
- Unique Constraint 정의
- 조회 조건 기준 Index 설계
- 생성·수정 시간 컬럼 공통화
- Enum 저장 방식 결정
- PostgreSQL 단일 쓰기 주체를 Spring Boot로 확정
- Migration 도구 결정
    - Spring Flyway 권장
- 원본 이벤트와 LLM 추출 결과 분리
- LLM 초안 라벨과 사람 검증 최종 라벨 분리
- 학습 피처와 운영 보조 피처 분리
- 모델 버전 및 규칙 버전 저장
- Mock 데이터와 실제 수집 데이터 출처 구분

## 공통 추적 컬럼

AI 및 이벤트 관련 테이블에는 필요한 경우 아래 컬럼을 둔다.

- `data_source`
- `is_mock`
- `model_version`
- `rule_version`
- `created_at`
- `updated_at`

## 구현 산출물

- ERD
- `V1__create_initial_schema.sql`
- PostgreSQL Docker Compose
- Spring Boot DB 설정
- Spring JPA/Repository 및 Flyway 설정
- Spring–FastAPI 분석 통신 DTO
- 테이블 및 제약조건 검증 SQL

## 완료 기준

- ERD v0.1 확정
- DDL 작성 완료
- `risk_events → event_extractions → event_labels → event_features` 관계 정의 완료
- 이벤트와 자재의 N:M 관계 정의 완료
- Flyway Migration 성공
- 로컬 PostgreSQL 테이블 생성 확인
- PK / FK / Unique / Index 생성 확인
- Spring Boot PostgreSQL 연결 성공
- FastAPI가 PostgreSQL 없이 정상 기동
- Spring이 DB Context를 조회해 FastAPI에 전달하는 통합 테스트 성공
- FastAPI 결과를 Spring이 PostgreSQL에 저장하는 통합 테스트 성공
- 이후 Schema 변경은 신규 Flyway Migration으로 관리

---

# ✅ 4순위. Mock ERP·계약·이벤트·학습 데이터 생성기 작성

## 목표

DB, Spring Boot, FastAPI, RAG, XGBoost, Severity Rule Engine 테스트에 공통으로 사용할
일관된 가상 데이터셋을 생성하고 PostgreSQL에 반복적으로 적재할 수 있는 환경을 만든다.

Mock 데이터는 다음 목적을 분리하여 생성한다.

- ERP·계약 조회 테스트
- 운영 이벤트 분석 테스트
- XGBoost 학습 파이프라인 구조 검증
- Severity 계산 검증
- 프론트엔드 시연

## Python Script

```
generate_mock_data.py
```

## 생성 파일

### 기준·ERP 데이터

```
materials.csv
suppliers.csv
supplier_materials.csv
inventories.csv
purchase_orders.csv
```

### 계약 데이터

```python
contracts.csv
contract_clauses.csv
contract_documents.csv
```

### 이벤트 원문 및 매핑 데이터

```python
risk_events.csv
risk_event_materials.csv
```

### LLM 추출 및 라벨 데이터

```python
event_extractions.csv
event_labels.csv
```

### 학습·추론 피처 데이터

```python
event_features.csv
```

### AI 결과 Demo 데이터

```python
analysis_results.csv
severity_results.csv
briefings.csv
briefing_references.csv
```

### AI 결과 Mock 데이터

```python
analysis_results.csv
severity_results.csv
briefings.csv
briefing_references.csv
```

## 생성 모드

### Base Mode

ERP·계약 및 이벤트 입력 데이터만 생성한다.

사용 목적:

- FastAPI 실제 분석 흐름 테스트
- XGBoost 추론 테스트
- Severity Rule Engine 테스트

### Training Mode

LLM 추출, 사람 검증 라벨, 최종 피처 구조를 포함한 학습용 Synthetic Data를 생성한다.

사용 목적:

- 학습 파이프라인 구조 검증
- Feature Schema 검증
- 클래스 분포 검증

### Demo Mode

분석 결과와 브리핑까지 미리 생성한다.

사용 목적:

- Spring Boot 조회 API 테스트
- React 화면 시연

#### 실행 예시:

```
python generate_mock_data.py--mode base--seed42
python generate_mock_data.py--mode training--seed42
python generate_mock_data.py--mode demo--seed42
```

## 데이터 범위

### 운영 및 Demo 데이터

- Lithium
- Nickel
- Cobalt
- Graphite
- Manganese

### Training Synthetic 데이터

- Lithium
- Nickel
- Cobalt
- Graphite
- Manganese
- Copper
- Iron Ore
- Rare Earth
- Aluminum
- Tin

학습 데이터는 넓게 구성하고,
운영 및 화면 조회에서는 배터리 원자재로 필터링한다.

## 현실성 반영 항목

- 핵심광물 여부
- 공급사 국가
- FEOC 여부
- 공급사별 자재 공급 관계
- 재고량 및 재고 소진 일수
- 안전재고 일수
- 계약 시작일·종료일
- 가격 조정 임계치
- 불가항력 조항
- 입고 예정일
- 공급사 의존도
- IATF 16949 및 PPAP 여부

## 생성 원칙

임의의 완전 독립 난수보다 관계가 유지되는 시나리오 데이터를 생성한다.

예:

```
폭우 이벤트 발생
→ 칠레 Lithium 관련
→ SQM 공급사 매칭
→ 재고 12일
→ 안전재고 20일
→ 계약 가격 조정 임계치 10%
→ Severity CRITICAL 예시 생성
```

## 구현 원칙

- 동일 Seed 사용 시 동일 데이터 생성
- FK 관계가 깨지지 않도록 ID 생성 순서 고정
- CRITICAL / WARNING / NORMAL 시나리오 모두 포함
- CSV 적재 순서 명시
- 중복 적재를 방지하거나 초기화 후 재적재 가능
- 날짜와 수치 범위가 현실적인 값이 되도록 제한

## 완료 기준

- CSV 자동 생성
- 동일 Seed로 재현 가능
- PostgreSQL Import 성공
- FK 및 Unique Constraint 오류 없음
- Spring Boot DB 조회 성공
- Spring Boot가 ERP Context를 조합해 FastAPI에 전달 성공
- FastAPI는 전달받은 Context만으로 분석 성공
- FastAPI 분석 결과를 Spring Boot가 PostgreSQL에 저장 성공
- CRITICAL / WARNING / NORMAL 시나리오 조회 확인

---

# ✅ 5순위. RAG 환경 구축 및 ChromaDB 초기화

## 목표

계약서와 규제 문서를 공급사·계약·자재 기준으로 안전하게 검색할 수 있는 RAG 기반을 구축한다.

## 환경 구성

- LangChain 또는 경량 자체 파이프라인
- ChromaDB
- Embedding Model
- PDF / TXT Loader
- 원본 문서 저장 디렉터리

## 계약서 업로드 선행 조건

계약서는 먼저 Spring Boot 또는 DB Seed를 통해 RDB에 등록되어 있어야 한다.

```
1. RDB에 Contract 생성
2. contract_id 발급
3. Spring Boot가 FastAPI에 PDF와 contract_id 업로드
4. 문서 청킹 및 ChromaDB 적재
5. FastAPI가 적재 결과를 Spring Boot에 반환
6. Spring Boot가 contract_documents 상태 갱신
```

## Chunking 전략

우선순위:

1. 계약 조항 단위
2. 제목 및 조항 번호 보존
3. 지나치게 긴 조항만 토큰 단위 추가 분할
4. Overlap은 보조적으로 적용

권장 초기값:

```
chunkSize: 700~1000 tokens
chunkOverlap: 100~150 tokens
```

## Metadata

API 및 DB 명명 규칙에 맞춰 `snake_case` 기준으로 정의한다.

```
document_id
contract_id
supplier_id
material_id
country_code
document_type
clause_type
effectiveDate
page_number
```

## 검색 원칙

- `contract_id` 또는 `supplier_id` 중 하나는 필수
- Metadata 하드 필터 후 Vector Similarity Search
- 다른 공급사의 계약서가 검색되지 않도록 제한
- 원문 위치 및 페이지 번호 반환

## 완료 기준

- PDF 또는 TXT 업로드 성공
- 계약 조항 단위 청킹 확인
- ChromaDB 적재 성공
- Metadata Filtering 테스트 성공
- 검색 결과에 문서·계약·페이지 정보 포함
- Spring 경유 업로드와 검색 호출 성공
- FastAPI 적재 실패 시 Spring의 contract_documents 상태가 FAILED로 갱신

---

# 📌 이후 핵심 구현

# 6. 데이터 수집 파이프라인

## MVP 우선순위

1. GDELT DOC API 또는 샘플 뉴스 입력
2. Open-Meteo
3. GDACS
4. yfinance
5. Google News RSS

## 고도화 대상

- BigQuery 과거 5개년 수집
- 관세청 수출입무역통계
- BDI
- AIS
- SEC EDGAR 자동 수집

## 저장 구조

```
External API
→ Spring Boot 분석 접수 또는 수집 Adapter
→ Spring Boot Raw Table 저장
→ 정규화
→ Feature Table 생성
```

---

# 7. Feature Engineering

## 뉴스 피처

- `tone_score`
- `goldstein_scale`
- `news_count`
- `actor1_type`
- `actor2_type`

## 환경 피처

- `rainfall_24h_mm`
- `gdacs_alert_level`
- `country_is_mining_hub`

## 시장 피처

- `stock_volatility_20d`

## 추가 운영 피처

- `stock_days`
- `supplierDependencyRatio`
- `feoc_status`

단, 학습 정의서의 XGBoost 학습 피처와 운영 중 ERP 보조 피처를 명확히 구분한다.

---

# 8. XGBoost 모델 학습

## 모델 역할

XGBoost는 사건의 유형인 Impact Domain을 분류한다.

```
PRODUCTION
LOGISTICS
POLICY
MARKET
GEOPOLITICS
```

정상·주의·심각은 XGBoost가 직접 분류하지 않는다.

## 작업 내용

- BigQuery 과거 데이터 수집
- LLM Impact Domain 초안 라벨 생성
- 사람 Blind Review
- Cohen’s Kappa 산출
- Feature Table 생성
- TimeSeriesSplit
- Logistic Regression Baseline
- XGBoost 학습
- Accuracy / Precision / Recall / F1 평가
- Confusion Matrix
- SHAP 분석
- Model Artifact 저장
- FastAPI 모델 로딩 및 추론

## 모델 산출물 예시

```
xgboost_impact_domain.json
feature_schema.json
class_mapping.json
model_metadata.json
```

---

# 9. Severity Rule Engine

## 역할

사건의 현재 심각도를 정상·주의·심각으로 계산한다.

```
Impact Domain = 무슨 종류의 사건인가
Severity = 현재 얼마나 심각한가
```

## 입력 예시

- Goldstein Scale
- Tone Score
- News Count
- GDACS Alert
- 강수량 임계치 초과 여부
- 주가 변동성
- 재고 소진 일수
- FEOC 또는 규제 위반 여부

## 출력

```
severity
score
reason_codes
calculation_details
rule_version
```

## 완료 기준

- 동일 입력에 대해 동일 결과 반환
- 가중치 및 임계치 설정 파일 분리
- 판정 사유 설명 가능

---

# 10. RAG·ERP Agent 및 브리핑 생성

Spring Boot가 PostgreSQL에서 ERP·계약 Context를 조회하여 FastAPI에 전달하고,
FastAPI는 전달받은 Context와 ChromaDB 검색 결과를 사용해 브리핑 초안을 생성한다.

## ERP 관점

- 현재 재고
- 안전재고
- 재고 소진 일수
- 발주 현황
- 입고 예정일
- 대체 공급사

## 계약 관점

- 가격 조정 조항
- 불가항력 조항
- 계약 유효기간
- 가격 조정 임계치
- 규제 적합성
- 공급사 품질 인증

## 브리핑 원칙

재고 관점과 계약 관점을 하나의 결론으로 합치지 않는다.

```
{
  "inventoryPerspective": {},
  "contractPerspective": {},
  "recommendedActions": [],
  "warnings": [],
  "references": []
}
```

AI는 의사결정 근거를 제시하며 최종 판단은 사용자가 수행한다.
FastAPI는 브리핑 초안을 반환하고, 최종 브리핑 및 참조 데이터의 PostgreSQL 저장은 Spring Boot가 담당한다.

---

# 11. 통합 테스트 및 배포

## 로컬 MVP

```
React
Spring Boot
FastAPI
PostgreSQL
ChromaDB
```

## 최종 배포

```
EC2
└─ Docker Compose
   ├─ nginx
   ├─ frontend
   ├─ spring-api
   ├─ fastapi-ai
   ├─ postgres
   └─ chromadb
```

## 테스트 대상

- React → Spring Boot
- Spring Boot → PostgreSQL
- Spring Boot → FastAPI `/api/v1/analyze`
- Spring Scheduler → Spring 분석 Orchestration Service
- Spring → FastAPI 요청에 ERP·계약 Context 포함
- FastAPI 응답 → Spring PostgreSQL 저장
- FastAPI가 PostgreSQL에 직접 접근하지 않는지 확인
- FastAPI → ChromaDB
- FastAPI → LLM API
- 예외 응답
- 외부 API 장애 시 Mock / Fallback
- CORS 및 Nginx Routing

---

# ✅ 12순위. Master Data·데이터 품질·계보

## 관련 기능

F6, F7, F8, D1~D4, C5

## 구현 작업

- 자재·공급사 Alias와 개체 매칭
- 국가·항만·통화·단위 표준화
- 뉴스 중복 제거와 사건 군집화
- 소스 신뢰도와 데이터 품질 상태
- 원본→Feature→분석 결과 Lineage
- ERP Context Snapshot과 분석 재현
- 규제 버전·효력·Hard Gate
- Idempotency 적용

## 완료 기준

- 동일 공급사·자재의 중복 개체가 생성되지 않음
- Invalid 데이터가 분석 전에 격리됨
- 동일 이벤트 재처리 시 중복 결과가 생성되지 않음
- 모든 분석 결과에서 입력 Snapshot과 근거 추적 가능
- 만료된 정책은 Severity 판정에 사용되지 않음

---

# ✅ 13순위. AI 학습·검증·모델 운영

## 관련 기능

M1~M6

## 구현 작업

- LLM 라벨 생성과 사람 Blind Review
- Cohen's Kappa와 최종 라벨 확정
- Dataset·Feature Manifest
- TimeSeriesSplit과 누출 방지
- Logistic Regression Baseline
- XGBoost 학습·평가
- SHAP 설명
- Model Registry와 승인·배포·Rollback
- 데이터·모델 Drift 모니터링

## 완료 기준

- 사람 검증 300~500건 또는 합의한 목표 달성
- 클래스당 최소 표본 확보
- 시계열 분할과 재현 가능한 학습 완료
- 모델 Artifact·Metadata·평가 지표 저장
- FastAPI가 승인된 모델만 로드
- 모델 설명과 브리핑 근거 방향 일치

---

# ✅ 14순위. 알림·대응 조치·사용자별 업무화

## 관련 기능

F10~F12, C2, C4

## 구현 작업

- BUYER/PLANNING/EXECUTIVE/ADMIN 권한
- 사용자별 관심 자재·공급사
- Severity 알림과 중복 억제
- Critical 미확인 에스컬레이션
- 권장 조치 Task 전환
- 담당자·기한·승인·완료
- 감사 로그

## 완료 기준

- 권한별 화면·API 접근 차단
- Critical 발생 시 지정 사용자에게 알림
- 조치 담당자와 완료 이력 추적
- 주요 변경·실행 이벤트 감사 가능

---

# ✅ 15순위. 선택적 고도화

## Knowledge Graph

- Neo4j 노드·관계 스키마
- 광산→자재→공급사→계약→고객 영향 경로
- 정책 유효성 노드
- PostgreSQL만으로도 핵심 기능이 유지되도록 선택 모듈화

## AIS 실시간 검증

- 물류 도메인에서만 호출
- ERP ETA와 실제 선박 위치·ETA 비교
- 괴리 경고와 Confidence 보정

## 과거 유사 사례 참고 범위

- 핵심 Severity와 분리
- 표본 수·관찰 범위·불확실성 표시
- 미래 예측이 아니라 과거 사례 참고임을 명시

## What-if 스트레스 테스트

- 입고 지연·공급 감소·대체 공급사 가격 가정
- 사용자 입력 가정 기반 계산
- 실제 예측과 구분하여 표시

---

# 📅 1일차 목표

- Interface Specification v0.2 확정
- Spring의 PostgreSQL 단일 쓰기 책임과 FastAPI 분석 책임 확정
- Spring Boot Dummy API 구현
- FastAPI 프로젝트 생성
- `/api/v1/analyze` Mock 구현
- Swagger 공유

## 1일차 완료 기준

- React가 Spring Boot Dummy API 호출 가능
- Spring Swagger 또는 API 문서 확인 가능
- FastAPI `/docs` 확인 가능
- `/analyze`가 명세 구조의 Mock 결과 반환
- 팀원이 Endpoint와 JSON 구조를 확인

---

# 📅 2일차 목표

- PostgreSQL ERD 및 DDL
- Mock ERP·계약 데이터 생성
- Spring Boot DB 연결
- Spring ERP·계약 Context 조회 및 FastAPI 전달
- Spring–FastAPI 분석 호출 연결
- FastAPI 결과 Spring 저장
- ChromaDB 초기 설정
- 샘플 계약서 업로드
- Metadata Filtering 검색 테스트

## 2일차 완료 기준

- 더미 API 일부를 DB 조회 방식으로 교체
- Mock CSV가 PostgreSQL에 적재됨
- Spring이 Context를 포함해 FastAPI `/analyze` 호출 성공
- Spring이 FastAPI 결과를 PostgreSQL에 저장
- 계약서 1개 이상 ChromaDB 적재
- `contract_id` 또는 `supplier_id` 필터 검색 성공

---

# 📅 MVP 일정

| 일정 | 주요 완료 내용 | 누적 진행률 |
| --- | --- | --- |
| 1일차 | 명세, Dummy API, FastAPI 구조, Swagger | 약 15~20% |
| 2일차 | ERD, PostgreSQL, Mock 데이터, RAG 기반 | 약 30~35% |
| 3~5일차 | 데이터 수집 일부, 피처 생성, End-to-End 연결 | 약 50~60% |
| 6~8일차 | XGBoost 초기 모델, Severity Rule Engine | 약 70~75% |
| 9~10일차 | RAG·ERP Context·브리핑 통합 | 약 85~90% |
| 최종 | 테스트, 예외 처리, Docker, 배포, 시연 | 100% |

진행률은 기반 작업 개수가 아니라 실제 기능 및 통합 완성도를 기준으로 계산한다.

---

# 🧭 기능–구현 단계 매핑

| 구현 단계 | 핵심 기능 | 권장 범위 |
| --- | --- | --- |
| 0~2순위 | API 계약, Dummy API, FastAPI Mock | MVP 필수 |
| 2.5~5순위 | 서비스 통합, DB, Mock 데이터, RAG 기반 | MVP 필수 |
| 6~9순위 | 외부 데이터, Feature, XGBoost, Severity | MVP 필수 |
| 10~11순위 | ERP·RAG 브리핑, E2E, 배포 | MVP 필수 |
| 12순위 | Master Data, 품질, 계보, 규제 Hard Gate | 운영 전 필수 |
| 13순위 | 학습 검증, Registry, Drift 모니터링 | 실제 모델 전환 시 필수 |
| 14순위 | 인증, 알림, 대응 조치, 감사 로그 | 실사용 전 필수 |
| 15순위 | Knowledge Graph, AIS, What-if | 선택 고도화 |

## MVP에서 반드시 보여줄 사용자 흐름

1. 외부 이벤트 또는 사용자 분석 요청을 Spring Boot가 접수한다.
2. Spring Boot가 ERP·계약 Context를 구성해 FastAPI `/api/v1/analyze`를 호출한다.
3. FastAPI가 Extraction → Impact Domain → Severity → RAG → Briefing을 수행한다.
4. Spring Boot가 응답을 검증하고 PostgreSQL에 결과와 근거를 저장한다.
5. React가 Spring Boot 조회 API로 리스크, 영향도, 근거, 권장 조치를 표시한다.
6. 실패 시 상태와 오류 원인을 확인하고 안전하게 재시도할 수 있다.

## MVP 이후로 미룰 수 있는 항목

- 실제 LLM·Embedding·XGBoost 모델 연동과 Model Registry
- Neo4j Knowledge Graph
- AIS 실시간 선박 추적
- Slack·Teams 등 외부 알림 채널
- 고급 What-if 시뮬레이션
- S3 등 외부 Object Storage

Mock 단계에서도 API 스키마, 책임 경계, 상태 전이, 근거 구조는 실제 운영 형태와 동일하게 유지한다.
