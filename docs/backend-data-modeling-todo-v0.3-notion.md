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

# 🎯 기능 공통 책임 원칙

기술 우선순위와 별개로 시스템이 제공해야 하는 업무 기능을 정의한다. 각 기능은 이후 0~15순위 구현 단계와 연결한다.

```
F 기능: 사용자가 직접 사용하는 업무 기능
C 기능: 서비스를 안전하게 운영하기 위한 공통 기반
M 기능: AI 모델을 신뢰하고 교체·운영하기 위한 기반
D 기능: AI에 입력되는 데이터를 깨끗하게 관리하는 기반
```

```python
React
  → Spring Boot만 호출

Spring Boot
  → 사용자 요청·스케줄링·ERP·계약·파일·PostgreSQL·조회 API 관리
  → FastAPI에 분석 Context 전달
  → FastAPI 결과 검증·저장·조회

FastAPI
  → LLM·Feature·XGBoost·Severity·RAG·브리핑 분석
  → ChromaDB 관리
  → 분석 결과와 근거를 Spring Boot에 반환

모델링 팀 로컬
  → 라벨링·학습 데이터·모델 학습·검증·SHAP 분석
  → 최종 모델 Artifact를 FastAPI 팀에 전달
```

FastAPI는 PostgreSQL에 직접 접근하지 않는다. Spring Boot는 ChromaDB를 직접 관리하지 않는다.

---

# 최종 담당 요약

| 기능 | Spring Boot | FastAPI | 모델링 팀 | 상태 | 담당자 |
| --- | --- | --- | --- | --- | --- |
| F1 ERP 영향 | ERP Context·저장 | 영향 해석 | - | 유지 |  |
| F2 RAG | 업로드·Metadata | ChromaDB·검색·답변 | - | 유지 |  |
| F3 AI 분석 | 요청·저장 | Extraction·XGBoost·Severity | Artifact 제공 | 유지 |  |
| F4 수집 | Scheduler·수집·저장 | AI 전처리 | - | 유지 |  |
| F5 브리핑 | 저장·조회·보고서 | 초안 생성 | - | 유지 |  |
| F6 Master Data | 최종 ID 관리 | 개체 후보 추출 | - | 유지 |  |
| F7 근거 계보 | 저장·조회 | 근거 생성·반환 | - | 유지 |  |
| F8 규제 | 유효 규제 Context | Hard Gate | - | 유지 |  |
| F9 대체 공급사 | 자격 필터 | 후보 비교 설명 | - | 유지 |  |
| F10 알림 | 정책·발송 | Severity 반환 | - | 축소 유지 |  |
| F11 대시보드 | 조회·집계 API | - | - | 유지 |  |
| F12 업무 추적 | - | - | - | 삭제 |  |
| C1 업로드 | 접수·관리 | 문서 처리 | - | 유지 | 김민지 |
| C2 인증 | 전담 | 내부 호출만 | - | 유지 | 김수린 |
| C3 작업·재시도 | 최소 상태 | 오류 응답 | - | 축소 유지 |  |
| C4 감사 로그 | 기본 로그만 | 기본 로그만 | - | 독립 기능 삭제 |  |
| C5 중복 방지 | 시스템 중복 방지 | RAG 중복 방지 | - | 유지 |  |
| C6 관측성 | Spring 측 Health | AI 측 Health | - | 축소 유지 |  |
| C7 LLM 검증 | 응답 계약 확인 | 프롬프트·출력 검증 | - | 축소 유지 |  |
| M1 라벨링 | - | - | 담당 | 백엔드 제외 |  |
| M2 Dataset | - | Schema 사용 | 담당 | 백엔드 제외 |  |
| M3 Registry | - | Artifact 로딩 | Artifact 제공 | Registry 삭제 |  |
| M4 학습 검증 | - | - | 담당 | 백엔드 제외 |  |
| M5 설명 가능성 | 근거 저장 | 기본 근거 반환 | 로컬 SHAP | 축소 유지 |  |
| M6 모니터링 | - | 로딩 Health만 | - | 삭제 |  |
| D1 중복 제거 | 전담 | 문서 ID 보조 | - | 축소 유지 |  |
| D2 소스 신뢰도 | 출처만 저장 | 판정 안 함 | - | 삭제 |  |
| D3 품질 검증 | 기본 DTO·DB 검증 | Pydantic 검증 | 로컬 데이터 검증 | 독립 기능 삭제 |  |
| D4 단위·통화 | 표준 Context | 표준값 사용 | - | 변환 기능 삭제 |  |

---

# 🤖  F — 업무 기능

## F1. ERP 영향 분석 AI 에이전트

### 어떤 기능인가?

외부에서 발견한 뉴스나 위험 사건이 우리 회사가 실제로 구매하는 자재·공급사·재고·계약에 어떤 영향을 주는지 연결해서 설명하는 기능이다. 예를 들어 칠레 리튬 공급 차질이 감지되면, 관련 공급사와 현재 재고 일수 및 계약 조건을 함께 확인하여 실제 구매 위험을 보여준다.

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

- Spring Boot: ERP 원본과 계산 가능한 업무 수치를 조회·구성하고 결과를 저장한다.
- FastAPI: 전달받은 사건과 ERP Context를 결합하여 영향과 권장 조치 초안을 생성한다.

### Spring Boot 기능

- ERP 자재·공급사·재고·발주·계약 조회
- 확정된 자재·공급사 매칭 결과 조회
- `stock_days`, `supplierDependencyRatio` 등 결정적 수치 계산 또는 구성
- 분석 시점의 ERP Context Snapshot 생성
- FastAPI `/api/v1/analyze` 호출
- 분석 결과와 근거 PostgreSQL 저장·조회

### FastAPI 기능

- 이벤트와 ERP Context 결합 분석
- 재고 소진 일수와 공급사 의존도의 업무적 의미 해석
- 자재별 영향도 생성
- 판단 근거와 경고 생성
- 권장 조치 초안 반환

## F2. RAG 기반 계약·대체 공급망 분석

### 어떤 기능인가?

계약서와 사내 지침에서 현재 리스크에 관련된 조항을 찾아 출처와 함께 보여주고, 그 내용을 바탕으로 계약상 대응 방안과 대체 공급사 검토 자료를 만드는 기능이다.

### 목표

계약서와 사내 문서를 안전하게 검색하여 계약상 대응 근거와 대체 공급망 검토 자료를 제공한다.

### 주요 기능

- PDF/TXT 업로드
- 조항 단위 청킹
- ChromaDB Embedding
- 계약·공급사·자재 Metadata Filter
- 원문·페이지·조항 근거 반환
- 계약 대응 방안 생성
- 적격 대체 공급사 후보 비교

### 책임

- Spring Boot: 파일 접수·기본 검증, 문서 ID와 Metadata 관리, FastAPI 호출, 처리 결과 저장
- FastAPI: 텍스트 추출·청킹·Embedding·검색, 근거 기반 계약 분석

### Spring Boot 기능

- 업로드 API와 사용자 요청 접수
- 계약·문서·공급사·자재 ID 확인
- 확장자·MIME·크기·중복 Hash 검증
- 문서 Metadata와 처리 결과 PostgreSQL 저장
- FastAPI RAG 업로드·검색 API 호출
- RAG 결과를 브리핑·조회 API에 연결

### FastAPI 기능

- PDF/TXT 텍스트 추출
- 계약 조항 단위 청킹
- Embedding과 ChromaDB 저장·검색
- `contract_id`, `supplier_id`, `material_id` Metadata Filter
- 원문·페이지·조항 근거 반환
- 계약 대응 방안과 후보 비교 설명 생성

## F3. AI 기반 공급망 리스크 분석

### 어떤 기능인가?

뉴스 원문에서 사건 정보를 뽑고, 사건이 생산·물류·정책·시장·지정학 중 어디에 해당하는지 분류한 뒤 현재 위험 수준을 정상·주의·심각으로 계산하는 핵심 AI 분석 기능이다.

### 목표

외부 리스크 사건을 정형 정보로 변환하고 영향 유형과 현재 심각도를 일관된 기준으로 판단한다.

### 주요 기능

- LLM 뉴스 정보 추출
- Feature 생성
- XGBoost Impact Domain 분류
- 규칙 기반 Severity 계산
- 판단 근거·Confidence·버전 반환
- Mock/실제 분석 결과 구분
- 기술적 실패 응답

### 책임

- Spring Boot: 분석 입력과 Context 준비, 분석 호출, 결과 저장·조회
- FastAPI: Extraction → Feature → Classification → Severity 파이프라인 수행

### Spring Boot 기능

- 이벤트 원문·출처·외부 Event ID 저장
- 분석 요청과 ERP·계약 Context 구성
- FastAPI 호출 및 Timeout 처리
- 응답 Schema 검증
- 성공·실패 결과와 분석 시각 저장

### FastAPI 기능

- LLM 또는 Mock Extraction
- 추론용 Feature 생성
- XGBoost로 Impact Domain 분류
- Rule Engine으로 NORMAL / WARNING / CRITICAL 계산
- `reason_codes`, `calculation_details`, 모델·규칙 버전 반환
- 근거 부족은 `warnings`로 반환

> 자동 재시도·실패 단계 재개·추가 근거 최대 2회 탐색은 MVP에서 제외한다.

> **구현 현황 (2026-07-30)** — 위 FastAPI 기능 중 "XGBoost로 Impact Domain 분류"는 구현되지 않았다.
> `/analyze`는 LLM 추출의 `impact_domain_draft`를 최종값으로 그대로 쓰며(`orchestration_service.py`),
> 분류 확률이 없어 `confidence`는 `null`이다. 자리만 지키던 규칙 기반 mock 분류기와
> `/internal/ml/classify` 엔드포인트는 2026-07-30에 제거했다(응답 스키마 `ClassificationResult`는 유지).
> **학습된 XGBoost가 실제로 도는 곳은 F4 트리아지 필터 하나뿐이다**(`app/models/triage_filter.json`).
> 

## F4. 외부 데이터 주기 수집

### 어떤 기능인가?

뉴스·재난·기후·가격·환율과 같은 외부 데이터를 정해진 시간마다 자동으로 가져와 새로운 공급망 위험 신호가 있는지 확인할 수 있게 만드는 기능이다.

### 목표

뉴스·기후·재난·가격 데이터를 정해진 주기로 수집하여 분석 가능한 사건 입력을 만든다.

### 주요 기능

- GDELT, GDACS, 기상, 주가·환율 수집
- 공식 가격·공시·ERP 데이터 주기 동기화
- Fast/Slow Track 실행 주기
- Full/Incremental Sync
- 마지막 성공 시각과 Cursor 관리
- 기본 중복 방지

### 책임

- Spring Boot: Scheduler와 수집 작업 관리, 원본 저장, 분석 시작
- FastAPI: 전달된 뉴스·사건의 AI 전처리와 분석

### Spring Boot 기능

- 데이터 소스별 Adapter 호출
- 수집 Scheduler 실행
- Cursor와 마지막 성공 시각 관리
- 원본·출처·수집 시각 PostgreSQL 저장
- 동일 URL·Event ID·Hash 중복 방지
- 신규 사건에 대한 분석 요청 생성

### FastAPI 기능

- 수집된 뉴스의 정보 추출
- 분석용 Feature 변환
- Impact Domain과 Severity 분석

## F5. 구매 브리핑·대시보드·보고서

### 어떤 기능인가?

분석 결과를 구매 담당자와 경영진이 바로 이해하고 활용할 수 있도록 위험 요약, 재고 영향, 계약 근거, 대체 공급사, 협상 포인트를 화면과 보고서 형태로 정리하는 기능이다.

### 목표

ERP 영향과 계약 근거를 구매 담당자와 의사결정자가 이해할 수 있는 형태로 제공한다.

### 주요 기능

- 재고 관점과 계약 관점 분리
- 자재·공급사 영향 요약
- 대체 공급사 비교
- 예상 원가 영향 데이터
- 협상 포인트와 권장 조치
- 근거 문서 참조
- 대시보드·보고서 제공

### 책임

- Spring Boot: 결과 저장, 조회·집계 API, 보고서 파일 제공
- FastAPI: 브리핑 본문과 권장 조치 초안 생성

### Spring Boot 기능

- 브리핑 결과와 참조 근거 저장
- Dashboard·Risk·Briefing 조회 API
- 역할별 통계와 그래프용 데이터 집계
- PDF/Excel 내보내기

### FastAPI 기능

- ERP 분석과 RAG 분석 결과 결합
- 재고 관점·계약 관점 별도 작성
- 협상 포인트와 권장 조치 초안 생성
- 확정 근거·참고 정보·경고 분리

## F6. 개체 식별·Master Data 정규화

### 어떤 기능인가?

뉴스·ERP·계약서에서 서로 다르게 표기된 동일 자재나 공급사를 하나의 내부 ID로 연결하는 기능이다. 예를 들어 `협력사A`, `(주)에이컴퍼니`, `A Company`가 같은 공급사임을 확인하여 중복이나 잘못된 연결을 방지한다.

### 목표

서로 다른 데이터 소스의 자재·공급사 표현을 동일한 내부 ID에 정확하게 연결한다.

### 주요 기능

- 자재·공급사 Alias
- Vendor ID·사업자등록번호 우선 매칭
- 국가·항만 코드 표준화
- 자동 매칭 후보와 Confidence
- 최종 매칭 결과 저장

### 책임

- Spring Boot: Master Data와 최종 매칭 결과 관리
- FastAPI: 비정형 문서에서 개체명 추출과 유사 후보 제시

### Spring Boot 기능

- 자재·공급사 Master와 Alias 테이블 관리
- 식별자 기반 확정 매칭
- 중복 개체 방지와 최종 내부 ID 확정
- 분석 Context에 확정 ID 포함

### FastAPI 기능

- 뉴스·계약서에서 자재·공급사·국가·항만명 추출
- 명칭 유사도 또는 LLM 기반 매칭 후보 반환
- 확정되지 않은 후보를 임의로 Master에 저장하지 않음

## F7. 출처·근거·데이터 계보

### 어떤 기능인가?

AI의 결론이 어떤 뉴스, ERP 수치, 계약서 조항, 모델 및 규칙에서 만들어졌는지 거슬러 확인할 수 있게 하는 기능이다. 사용자가 결과를 그대로 믿는 대신 원본 근거를 직접 확인할 수 있게 한다.

### 목표

모든 분석 결과가 어떤 원문·ERP 수치·계약 조항·모델·규칙에서 만들어졌는지 추적할 수 있게 한다.

### 주요 기능

- 원본 URL과 수집 시각
- ERP Context Snapshot
- RAG 문서·페이지·조항
- 모델·규칙 버전과 reason_codes
- 확정 근거·참고 정보·경고 구분

### 책임

- Spring Boot: 원본과 분석 결과의 관계를 PostgreSQL에 보존하고 조회 API 제공
- FastAPI: 분석에 실제 사용한 근거와 버전을 응답에 포함

### Spring Boot 기능

- 원문·출처·수집 시각 저장
- ERP Snapshot과 분석 결과 연결
- FastAPI가 반환한 참조 근거 저장
- React가 근거를 조회할 API 제공

### FastAPI 기능

- RAG 문서·페이지·조항 반환
- `reason_codes`, `rule_version`, `model_version` 반환
- Mock/실제 분석 구분
- 근거 부족 경고 반환

## F8. 규제 Hard Gate·정책 유효성

### 어떤 기능인가?

FEOC·CRMA와 같이 위반 자체가 중대한 규제를 확인하고, 현재 유효한 규제를 위반한 경우 다른 조건이 양호하더라도 위험 등급을 강제로 심각으로 올리는 기능이다.

### 목표

현재 유효한 FEOC·CRMA 등의 규제 위반이 확인되면 다른 조건과 무관하게 Severity에 강제 반영한다.

### 주요 기능

- 규제 적용 지역·유효 기간·상태 관리
- 공급사 규제 상태 확인
- 유효한 규제만 분석에 적용
- 위반 시 CRITICAL Hard Gate
- 적용 규제와 근거 반환

### 책임

- Spring Boot: 규제와 공급사 사실 데이터 관리, 분석 시점 Context 구성
- FastAPI: 전달받은 규제 Context를 Severity Rule Engine에 적용

### Spring Boot 기능

- 규제 Metadata·버전·유효 기간 저장
- 공급사 FEOC 등 규제 상태 조회
- 분석 시점에 유효한 규제만 FastAPI에 전달

### FastAPI 기능

- 규제 Hard Gate 조건 평가
- CRITICAL 강제 격상
- 적용한 규제 코드와 reasonCode 반환

## F9. 공급사 자격·대체 공급사 추천

### 어떤 기능인가?

기존 공급사가 위험할 때 아무 공급사나 추천하지 않고, 품질 인증·규제 적합성·생산 능력·납기 조건을 충족한 실제 거래 가능 후보만 선별하여 비교하는 기능이다.

### 목표

실제 구매 후보가 될 수 있는 적격 공급사만 선별하고 상황별 장단점을 설명한다.

### 주요 기능

- IATF 16949·PPAP 확인
- FEOC·규제 적합성 확인
- 배터리급 자재 공급 가능 여부
- 생산 능력·MOQ·Lead Time·가격 비교
- 적격 후보 필터와 추천 근거

### 책임

- Spring Boot: 자격 사실 조회와 필수 조건 필터링
- FastAPI: 적격 후보 비교와 추천 사유 생성

### Spring Boot 기능

- 공급사 자격·인증·규제·납기·단가 조회
- 자격 미달 후보 제거
- 적격 후보 Context를 FastAPI에 전달

### FastAPI 기능

- 적격 후보 간 장단점 비교
- 현재 리스크 상황에 맞는 후보 우선순위 초안
- 추천 근거와 주의사항 반환
- 자격 미달 후보를 새로 생성하지 않음

## F10. 알림·에스컬레이션

### 어떤 기능인가?

주의 또는 심각한 위험이 발생했을 때 담당자가 놓치지 않도록 알림을 보내는 기능이다. 심각한 사건은 즉시 알리고 주의 사건은 일일 브리핑에 포함하는 방식으로 동작한다.

### 목표

리스크 등급에 따라 필요한 사용자에게 적절한 시점에 정보를 전달한다.

### 주요 기능

- Critical 즉시 알림
- Warning 일일 브리핑 포함
- 중복 알림 방지
- 발송 대상과 결과 관리

### 책임

- Spring Boot: 알림 정책 판단과 실제 발송
- FastAPI: Severity와 알림 판단 근거만 반환

### Spring Boot 기능

- Severity별 알림 정책
- 이메일 등 알림 채널 연동
- 중복 발송 방지와 발송 결과 기록

### FastAPI 기능

- Severity와 reason_codes 반환
- 이메일·Slack 등을 직접 호출하지 않음

## F11. 사용자 계층별 대시보드

### 어떤 기능인가?

같은 분석 결과를 구매팀에는 상세한 실행 정보로, 경영기획에는 집계와 추이로, 경영진에는 핵심 KPI로 보여주는 역할별 화면 지원 기능이다.

### 목표

구매팀·경영기획·경영진이 각 역할에 필요한 수준으로 리스크를 확인하게 한다.

### 주요 기능

- 구매팀 상세 리스크·재고·계약 정보
- 경영기획 사업부·공급사·지역별 통계
- 경영진 핵심 KPI와 Critical 요약

### 책임

- Spring Boot: 역할별 조회·집계 API와 접근 제어
- FastAPI: 별도의 사용자별 대시보드 기능 없음

### Spring Boot 기능

- 역할별 Dashboard API
- PostgreSQL 집계
- 접근 권한 적용

### FastAPI 기능

- 공통 분석 결과만 생성
- 사용자 역할별 화면 데이터는 생성하지 않음

## F12. 대응 조치·업무 추적

### 어떤 기능인가?

AI가 제안한 권장 조치를 담당자·기한·진행 상태가 있는 실제 업무로 바꾸고 완료까지 추적하는 기능이다. 현재 프로젝트에서는 부가적인 업무관리 범위로 판단하여 구현하지 않는다.

### 상태

MVP와 현재 백엔드 범위에서 삭제한다.

### 대체 범위

- FastAPI는 브리핑에 `recommendedActions` 초안만 반환한다.
- Spring Boot는 별도의 담당자·기한·승인·완료 Task 기능을 구현하지 않는다.

---

# 🧩 C - 공통 기반 기능

## C1. 파일 업로드

### 어떤 기능인가?

사용자가 계약서나 구매 지침 PDF/TXT 파일을 올리면 기본적인 안전 검사를 거쳐 RAG가 검색할 수 있는 문서로 처리하는 기능이다.

### 목표

계약서와 지침 문서를 안전하게 접수하여 RAG 처리로 연결한다.

### 주요 기능

- Multipart 업로드
- 확장자·MIME·크기 검증
- 문서 Hash 중복 방지
- 문서 Metadata와 처리 결과 저장
- RAG 적재 호출

### 책임

- Spring Boot: 외부 업로드 API와 문서 관리
- FastAPI: 문서 내용 처리와 ChromaDB 적재

### Spring Boot 기능

- 사용자 업로드 요청 접수
- 파일 기본 검증과 문서 ID 발급
- Metadata·처리 결과 PostgreSQL 저장
- FastAPI 업로드 API 호출

### FastAPI 기능

- 텍스트 추출·청킹·Embedding
- ChromaDB 적재
- 처리 결과와 오류 반환

## C2. 인증·권한

### 어떤 기능인가?

로그인한 사용자가 누구인지 확인하고 구매팀·경영진·관리자 등의 역할에 따라 볼 수 있는 데이터와 사용할 수 있는 API를 제한하는 기능이다.

### 목표

사용자를 식별하고 역할에 따라 API와 내부 데이터 접근을 제한한다.

### 주요 기능

- 로그인·로그아웃
- 비밀번호 암호화
- JWT
- 역할 기반 접근 제어

### 책임

- Spring Boot: 사용자 인증과 권한 전담
- FastAPI: 외부 사용자 인증 없음, Spring 내부 호출만 허용

### Spring Boot 기능

- 사용자·역할 관리
- JWT 발급·검증
- API 접근 제어

### FastAPI 기능

- 필요 시 내부 API Key 등 서비스 간 인증만 적용

## C3. 분석 작업·재시도

### 어떤 기능인가?

AI 분석이 진행 중인지, 완료됐는지, 기술적으로 실패했는지 상태와 원인을 관리하고 필요한 경우 다시 실행하는 기능이다. 현재는 자동 재시도를 제외하고 성공·실패 확인만 유지한다.

### 상태

복잡한 작업·재시도 기능은 삭제하고 최소 성공·실패 처리만 유지한다.

### 목표

분석 요청의 성공 여부와 기술적 실패 원인을 확인할 수 있게 한다.

### 주요 기능

- `COMPLETED / FAILED`
- 오류 코드와 처리 시각
- 근거 부족은 실패가 아니라 `warnings`로 반환

### 책임

- Spring Boot: 결과 상태 저장과 Timeout 처리
- FastAPI: 성공 응답 또는 구조화된 오류 응답 반환

### Spring Boot 기능

- FastAPI 호출 성공·실패 저장
- Timeout과 HTTP 오류 매핑

### FastAPI 기능

- 공통 오류 Schema
- 근거 부족 경고 반환
- 자동 재시도와 추가 탐색은 수행하지 않음

## C4. 감사 로그

### 어떤 기능인가?

누가 언제 로그인하거나 문서를 변경하고 분석 결과를 승인했는지 사용자 행동 이력을 별도로 보관하는 기능이다. 현재 프로젝트에서는 범위가 커지는 것을 막기 위해 구현하지 않는다.

### 상태

독립 기능으로 구현하지 않는다.

### 대체 범위

- Spring Boot와 FastAPI에 분석 ID, 성공·실패, 오류, 처리 시간의 기본 애플리케이션 로그만 남긴다.

## C5. Idempotency·기본 중복 방지

### 어떤 기능인가?

네트워크 재요청이나 반복 수집이 발생해도 동일한 사건·문서·분석 결과가 두 번 생성되지 않게 하는 기능이다.

### 목표

같은 사건·문서·분석 요청이 중복 저장되거나 실행되지 않게 한다.

### 주요 기능

- 외부 Event ID 중복 방지
- URL·원문 Hash 중복 방지
- 문서 Hash 중복 방지
- 동일 분석 ID 중복 처리 방지

### 책임

- Spring Boot: 시스템 차원의 중복 저장·요청 방지
- FastAPI: ChromaDB 문서와 동일 분석 요청의 중복 처리 방지

### Spring Boot 기능

- PostgreSQL Unique Constraint
- 이벤트·문서·분석 요청 중복 검사

### FastAPI 기능

- 동일 document_id의 중복 적재 방지
- 동일 analysis_id에 대한 안전한 응답

## C6. 관측성·Health Check

### 어떤 기능인가?

Spring Boot, FastAPI, 데이터베이스와 AI 모델이 정상인지 확인하고 문제가 발생했을 때 어느 단계에서 실패했는지 찾을 수 있게 하는 기능이다.

### 목표

각 구성 요소가 정상인지와 분석 실패 위치를 확인할 수 있게 한다.

### 주요 기능

- Health API
- Trace ID
- 오류와 처리 시간 로그
- 마지막 수집·분석 성공 시각

### 책임

- Spring Boot와 FastAPI가 각자 소유한 구성 요소의 상태를 제공한다.

### Spring Boot 기능

- Spring·PostgreSQL·Scheduler·FastAPI 연결 상태
- 수집·호출 로그와 Trace ID

### FastAPI 기능

- FastAPI·모델 Artifact·ChromaDB·LLM 상태
- 분석 단계별 오류와 처리 시간

## C7. LLM 응답 검증

### 어떤 기능인가?

FastAPI가 관리하는 고정 프롬프트와 출력 Schema를 사용하여 LLM이 잘못된 형식, 근거 없는 계약 조항 또는 금지된 예측 내용을 최종 결과로 반환하지 못하게 하는 기능이다.

### 목표

LLM의 잘못된 출력 형식, 근거 없는 계약 답변, 금지된 예측 표현이 시스템 결과로 확정되는 것을 막는다.

### 주요 기능

- FastAPI 내부 프롬프트 관리
- Pydantic JSON Schema 검증
- Enum과 필수 필드 검증
- RAG 근거 없는 계약 분석 차단
- 미래 예측 표현 제한

### 책임

- Spring Boot: 최종 FastAPI 응답의 Interface Schema 검증
- FastAPI: 프롬프트와 LLM 출력의 실질적 검증

### Spring Boot 기능

- 응답 필수값·Enum·HTTP 상태 검증
- 잘못된 응답 저장 차단

### FastAPI 기능

- 프롬프트를 코드 또는 버전 파일로 관리
- LLM 원문을 Pydantic 모델로 검증
- 근거가 없으면 `null`과 `warnings` 반환
- 고급 Prompt Injection 방어는 실제 LLM 연동 시 검토

---

---

# 🤖 M — 모델링 기능

M1~M6은 원칙적으로 Spring Boot 기능이 아니다. 현재 범위에서는 모델링 팀이 로컬에서 수행하고, FastAPI는 최종 Artifact를 받아 추론만 수행한다.

## M1. 사람 검증 라벨링

### 어떤 기능인가?

LLM이 임시로 붙인 사건 분류 라벨을 사람이 직접 검토하여 모델이 학습할 최종 정답을 만드는 작업이다. 백엔드 기능이 아니라 모델링 팀의 로컬 작업이다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 목표·주요 기능

- LLM 초안 라벨을 사람이 검증
- 최종 학습 라벨 확정

### 책임

- 모델링 팀: 전체 수행
- Spring Boot: 기능 없음
- FastAPI: 기능 없음

## M2. Dataset·Feature 버전 관리

### 어떤 기능인가?

모델이 어떤 기간의 데이터와 어떤 입력 변수로 학습되었는지 기록하여 동일한 학습 결과를 다시 만들 수 있게 하는 모델링 관리 작업이다. 백엔드에서는 구현하지 않는다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 목표·주요 기능

- 학습 Dataset과 Feature 정의 재현
- 학습 데이터 설명 자료 관리

### 책임

- 모델링 팀: 전체 수행
- Spring Boot: 기능 없음
- FastAPI: 전달받은 `feature_schema.json`만 사용

## M3. Model Registry

### 어떤 기능인가?

여러 모델 버전의 성능·승인·배포 상태를 관리하는 기능이다. 현재는 완전한 관리 시스템을 만들지 않고 FastAPI가 최종 모델 파일과 입력 규격을 읽는 기능만 유지한다.

### 상태

완전한 Registry는 삭제하고 Artifact 로딩 규격만 유지한다.

### 목표

FastAPI가 전달받은 최종 모델과 입력·출력 규격을 정확하게 로드한다.

### 주요 기능

- 모델 파일
- Feature Schema
- Class Mapping
- 최소 Model Metadata

### 책임

- 모델링 팀: 최종 Artifact 생성·전달
- Spring Boot: 기능 없음
- FastAPI: Artifact 로딩과 추론

### Spring Boot 기능

- 없음

### FastAPI 기능

- `ml/artifacts`에서 모델 로드
- Feature 이름·순서 검증
- Class Mapping 적용
- 모델 로딩 Health 상태 제공

## M4. 시계열 학습 검증

### 어떤 기능인가?

과거 데이터로 학습하고 이후 시점 데이터로 평가하여 미래 정보 유출이나 같은 사건의 중복 기사로 성능이 과장되지 않도록 검증하는 모델링 작업이다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 책임

- 모델링 팀: TimeSeriesSplit, 데이터 누수 방지, 성능 평가
- Spring Boot: 기능 없음
- FastAPI: 기능 없음

## M5. 설명 가능성

### 어떤 기능인가?

모델이 왜 특정 사건을 해당 유형이나 등급으로 판단했는지 Confidence, 주요 입력값, 규칙 근거 등으로 설명하는 기능이다. 현재 백엔드는 기본 근거만 전달하고 상세 SHAP 검증은 모델링 팀이 수행한다.

### 상태

실시간 SHAP 기능은 삭제하고 기본 분석 근거만 유지한다.

### 목표

백엔드는 사용자가 결과를 이해하는 데 필요한 최소 근거를 전달한다.

### 주요 기능

- Classification Confidence
- Severity reason_codes와 계산 값
- ERP 수치와 RAG 참조 근거

### 책임

- 모델링 팀: 로컬 SHAP 검증
- Spring Boot: 근거 저장·조회
- FastAPI: 추론 결과에 기본 근거 필드 포함

### Spring Boot 기능

- FastAPI 근거 저장과 조회 API 제공

### FastAPI 기능

- Confidence, reason_codes, calculation_details 반환
- 실시간 SHAP 계산은 수행하지 않음

## M6. 모델·데이터 모니터링

### 어떤 기능인가?

운영 중 입력 데이터의 분포나 모델 성능이 학습 당시와 달라지는지 장기간 감시하는 기능이다. 현재 프로젝트는 장기 운영을 목표로 하지 않으므로 구현하지 않는다.

### 상태

구현하지 않는다.

### 대체 범위

- FastAPI Health Check에서 모델 파일 로딩 성공 여부만 확인한다.
- Drift·성능 추적·자동 Rollback은 구현하지 않는다.

---

# 🤖 D — 데이터 처리 기능

## D1. 기본 중복 제거

### 어떤 기능인가?

동일한 사건을 다룬 기사나 같은 문서가 반복 수집되어 위험 점수와 알림 횟수가 부풀려지지 않도록 중복 데이터를 걸러내는 기능이다.

### 상태

고급 사건 군집화는 보류하고 기본 중복 제거만 유지한다.

### 목표

동일한 기사·사건·문서가 중복 저장되어 Severity와 알림이 왜곡되는 것을 막는다.

### 주요 기능

- URL 정규화
- 외부 Event ID 중복 확인
- 제목·본문·문서 Hash 확인
- PostgreSQL Unique Constraint

### 책임

- Spring Boot: 기본 중복 제거 전담
- FastAPI: 고급 임베딩 군집화는 구현하지 않음

### Spring Boot 기능

- 이벤트·기사·문서 중복 검사
- 중복 데이터 저장 차단

### FastAPI 기능

- ChromaDB의 동일 document_id 중복 적재 방지

## D2. 소스 신뢰도

### 어떤 기능인가?

정부 공식 자료, 주요 언론, 산업 매체 등의 출처를 신뢰 등급으로 나누어 분석에 반영하는 기능이다. 객관적인 판정 기준이 부족하므로 등급화는 삭제하고 출처 정보만 보존한다.

### 상태

주관적인 소스 신뢰도 판정 기능은 삭제한다.

### 대체 범위

- Spring Boot는 `source_name`, `source_url`, `published_at`, `collectedAt`을 사실 정보로 저장한다.
- FastAPI는 언론사를 신뢰·불신 등급으로 임의 분류하지 않는다.
- ERP·계약 근거, 뉴스 참고 정보, 근거 부족 경고의 구분은 유지한다.

## D3. 데이터 품질 검증

### 어떤 기능인가?

음수 재고, 잘못된 날짜, 존재하지 않는 공급사, 허용되지 않은 Enum처럼 분석 결과를 깨뜨릴 수 있는 입력 오류를 확인하는 기능이다. 별도 품질 시스템 대신 각 API의 기본 검증만 유지한다.

### 상태

독립 품질 관리 시스템은 삭제하고 서비스 경계의 기본 입력 검증만 유지한다.

### 목표

잘못된 타입·범위·식별자가 분석과 저장 단계로 들어오지 않게 한다.

### 주요 기능

- 필수값·타입·Enum 검증
- 비율과 수량 범위 검증
- FK·Unique Constraint

### 책임

- Spring Boot: DTO·업무 규칙·DB Constraint 검증
- FastAPI: Pydantic 분석 입력 검증

### Spring Boot 기능

- 필수 ID와 날짜·수량 범위 검증
- 자재·공급사 존재 여부 확인
- DB FK·Unique Constraint 적용

### FastAPI 기능

- 필수 Feature와 타입 검증
- Enum·비율 범위 검증
- 잘못된 입력에 공통 422 오류 반환

## D4. 단위·통화 표준

### 어떤 기능인가?

시장 가격·계약 단가·재고·발주량을 서로 비교할 때 kg, ton, USD 등의 기준이 달라 계산이 틀리지 않도록 API에서 사용할 기준 단위를 고정하는 기능이다.

### 상태

별도 변환 서비스를 구현하지 않고 Interface Specification에서 표준 단위를 고정한다.

### 목표

Spring Boot와 FastAPI가 동일한 가격·수량·비율 단위를 사용하도록 보장한다.

### 주요 기능

- 재고·발주량: kg
- 가격: USD/kg
- 가격 변동률·의존도: percent
- 재고 소진 기간: day
- 환율: 명시된 통화쌍 기준

### 책임

- Spring Boot: 수집·Mock·ERP Context를 표준 단위로 구성
- FastAPI: 전달받은 표준 단위를 변환하지 않고 분석

### Spring Boot 기능

- 외부 데이터 Adapter 또는 적재 단계에서 필요한 변환 수행
- 필드명과 단위를 API 명세에 명시

### FastAPI 기능

- 표준 단위를 전제로 Feature와 Severity 계산
- 입력 단위가 명세와 다르면 오류 반환

---

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
```

> **폐기 (2026-07-27):** 위 계획의 `GET /api/v1/risks`, `/risks/{risk_id}`, `/risks/{risk_id}/briefing`는 폐기 → 리스크 목록은 `GET /api/v1/risk-events`(프론트 `RiskEvent` 계약, 데이터는 F3/F4 모델 전까지 placeholder)가 대체, 브리핑 상세는 `GET /api/v1/briefings/{briefingId}`가 담당한다. `/analyses`, `/contracts/{id}`도 미구현.

## FastAPI 핵심 API

```
POST /api/v1/analyze
POST /api/v1/rag/contracts
POST /api/v1/rag/search
```

## FastAPI 개발·테스트용 내부 API

```
POST /api/v1/internal/llm/extract
POST /api/v1/internal/severity/score
POST /api/v1/internal/briefings
```

> `POST /api/v1/internal/ml/classify`는 2026-07-30에 폐지됐다 — 규칙 기반 mock이었고 호출자가 없었다.

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
```

> **폐기 (2026-07-27):** `GET /api/v1/risks`, `/risks/{risk_id}`, `/risks/{risk_id}/briefing`는 폐기 → `GET /api/v1/risk-events`(프론트 `RiskEvent` 계약)로 대체, 브리핑 상세는 `GET /api/v1/briefings/{briefingId}`가 담당한다. 아래 단건 `GET /api/v1/risks/{id}` 테스트 예시도 폐기(리스크는 목록만 제공, 단건 없음).

## 더미 응답 원칙

단순한 임시 JSON이 아니라 실제 DTO 구조와 동일한 응답을 반환한다.

```
{
  "success":true,
  "data": {
    "risk_id":101,
    "title":"칠레 리튬 생산 지역 폭우 발생",
    "material_id":1,
    "material_name":"Lithium",
    "supplier_id":11,
    "supplier_name":"SQM",
    "country_code":"CL",
    "impact_domain":"PRODUCTION",
    "severity":"CRITICAL",
    "severity_score":87.3,
    "stock_days":12,
    "evidence_type":"CONFIRMED",
    "detected_at": "2026-07-20T08:45:00+09:00"
  },
  "timestamp":"2026-07-20T09:30:00+09:00"
}
```

## 완료 기준

- 6개 API HTTP 200 자동 테스트 통과
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
POST /api/v1/internal/severity/score
POST /api/v1/internal/briefings
```

> `POST /api/v1/internal/ml/classify`는 2026-07-30에 폐지됐다 — 규칙 기반 mock이었고 호출자가 없었다.

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
- Alias Generator로 `snake_case` 직렬화

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

```
POST /api/v1/analyses
GET /api/v1/analyses/{analysis_id}
```

## Spring → FastAPI 내부 호출

```
POST http://{fastapi-host}:8000/api/v1/analyze
```

요청에는 다음을 포함한다.

- Event 원문과 출처
- Spring이 조회한 자재·공급사·재고·발주 Context
- 계약 ID 및 RAG 검색 범위
- Feature Override
- 실행 Option

## 처리 흐름

```
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

## F1. ERP 영향 분석 AI 에이전트

### 어떤 기능인가?

외부에서 발견한 뉴스나 위험 사건이 우리 회사가 실제로 구매하는 자재·공급사·재고·계약에 어떤 영향을 주는지 연결해서 설명하는 기능이다. 예를 들어 칠레 리튬 공급 차질이 감지되면, 관련 공급사와 현재 재고 일수 및 계약 조건을 함께 확인하여 실제 구매 위험을 보여준다.

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

- Spring Boot: ERP 원본과 계산 가능한 업무 수치를 조회·구성하고 결과를 저장한다.
- FastAPI: 전달받은 사건과 ERP Context를 결합하여 영향과 권장 조치 초안을 생성한다.

### Spring Boot 기능

- ERP 자재·공급사·재고·발주·계약 조회
- 확정된 자재·공급사 매칭 결과 조회
- `stock_days`, `supplierDependencyRatio` 등 결정적 수치 계산 또는 구성
- 분석 시점의 ERP Context Snapshot 생성
- FastAPI `/api/v1/analyze` 호출
- 분석 결과와 근거 PostgreSQL 저장·조회

### FastAPI 기능

- 이벤트와 ERP Context 결합 분석
- 재고 소진 일수와 공급사 의존도의 업무적 의미 해석
- 자재별 영향도 생성
- 판단 근거와 경고 생성
- 권장 조치 초안 반환

## F2. RAG 기반 계약·대체 공급망 분석

### 어떤 기능인가?

계약서와 사내 지침에서 현재 리스크에 관련된 조항을 찾아 출처와 함께 보여주고, 그 내용을 바탕으로 계약상 대응 방안과 대체 공급사 검토 자료를 만드는 기능이다.

### 목표

계약서와 사내 문서를 안전하게 검색하여 계약상 대응 근거와 대체 공급망 검토 자료를 제공한다.

### 주요 기능

- PDF/TXT 업로드
- 조항 단위 청킹
- ChromaDB Embedding
- 계약·공급사·자재 Metadata Filter
- 원문·페이지·조항 근거 반환
- 계약 대응 방안 생성
- 적격 대체 공급사 후보 비교

### 책임

- Spring Boot: 파일 접수·기본 검증, 문서 ID와 Metadata 관리, FastAPI 호출, 처리 결과 저장
- FastAPI: 텍스트 추출·청킹·Embedding·검색, 근거 기반 계약 분석

### Spring Boot 기능

- 업로드 API와 사용자 요청 접수
- 계약·문서·공급사·자재 ID 확인
- 확장자·MIME·크기·중복 Hash 검증
- 문서 Metadata와 처리 결과 PostgreSQL 저장
- FastAPI RAG 업로드·검색 API 호출
- RAG 결과를 브리핑·조회 API에 연결

### FastAPI 기능

- PDF/TXT 텍스트 추출
- 계약 조항 단위 청킹
- Embedding과 ChromaDB 저장·검색
- `contract_id`, `supplier_id`, `material_id` Metadata Filter
- 원문·페이지·조항 근거 반환
- 계약 대응 방안과 후보 비교 설명 생성

## F3. AI 기반 공급망 리스크 분석

### 어떤 기능인가?

뉴스 원문에서 사건 정보를 뽑고, 사건이 생산·물류·정책·시장·지정학 중 어디에 해당하는지 분류한 뒤 현재 위험 수준을 정상·주의·심각으로 계산하는 핵심 AI 분석 기능이다.

### 목표

외부 리스크 사건을 정형 정보로 변환하고 영향 유형과 현재 심각도를 일관된 기준으로 판단한다.

### 주요 기능

- LLM 뉴스 정보 추출
- Feature 생성
- XGBoost Impact Domain 분류
- 규칙 기반 Severity 계산
- 판단 근거·Confidence·버전 반환
- Mock/실제 분석 결과 구분
- 기술적 실패 응답

### 책임

- Spring Boot: 분석 입력과 Context 준비, 분석 호출, 결과 저장·조회
- FastAPI: Extraction → Feature → Classification → Severity 파이프라인 수행

### Spring Boot 기능

- 이벤트 원문·출처·외부 Event ID 저장
- 분석 요청과 ERP·계약 Context 구성
- FastAPI 호출 및 Timeout 처리
- 응답 Schema 검증
- 성공·실패 결과와 분석 시각 저장

### FastAPI 기능

- LLM 또는 Mock Extraction
- 추론용 Feature 생성
- XGBoost로 Impact Domain 분류
- Rule Engine으로 NORMAL / WARNING / CRITICAL 계산
- `reason_codes`, `calculation_details`, 모델·규칙 버전 반환
- 근거 부족은 `warnings`로 반환

> 자동 재시도·실패 단계 재개·추가 근거 최대 2회 탐색은 MVP에서 제외한다.

> **구현 현황 (2026-07-30)** — 위 FastAPI 기능 중 "XGBoost로 Impact Domain 분류"는 구현되지 않았다.
> `/analyze`는 LLM 추출의 `impact_domain_draft`를 최종값으로 그대로 쓰며(`orchestration_service.py`),
> 분류 확률이 없어 `confidence`는 `null`이다. 자리만 지키던 규칙 기반 mock 분류기와
> `/internal/ml/classify` 엔드포인트는 2026-07-30에 제거했다(응답 스키마 `ClassificationResult`는 유지).
> **학습된 XGBoost가 실제로 도는 곳은 F4 트리아지 필터 하나뿐이다**(`app/models/triage_filter.json`).
> 

## F4. 외부 데이터 주기 수집

### 어떤 기능인가?

뉴스·재난·기후·가격·환율과 같은 외부 데이터를 정해진 시간마다 자동으로 가져와 새로운 공급망 위험 신호가 있는지 확인할 수 있게 만드는 기능이다.

### 목표

뉴스·기후·재난·가격 데이터를 정해진 주기로 수집하여 분석 가능한 사건 입력을 만든다.

### 주요 기능

- GDELT, GDACS, 기상, 주가·환율 수집
- 공식 가격·공시·ERP 데이터 주기 동기화
- Fast/Slow Track 실행 주기
- Full/Incremental Sync
- 마지막 성공 시각과 Cursor 관리
- 기본 중복 방지

### 책임

- Spring Boot: Scheduler와 수집 작업 관리, 원본 저장, 분석 시작
- FastAPI: 전달된 뉴스·사건의 AI 전처리와 분석

### Spring Boot 기능

- 데이터 소스별 Adapter 호출
- 수집 Scheduler 실행
- Cursor와 마지막 성공 시각 관리
- 원본·출처·수집 시각 PostgreSQL 저장
- 동일 URL·Event ID·Hash 중복 방지
- 신규 사건에 대한 분석 요청 생성

### FastAPI 기능

- 수집된 뉴스의 정보 추출
- 분석용 Feature 변환
- Impact Domain과 Severity 분석

## F5. 구매 브리핑·대시보드·보고서

### 어떤 기능인가?

분석 결과를 구매 담당자와 경영진이 바로 이해하고 활용할 수 있도록 위험 요약, 재고 영향, 계약 근거, 대체 공급사, 협상 포인트를 화면과 보고서 형태로 정리하는 기능이다.

### 목표

ERP 영향과 계약 근거를 구매 담당자와 의사결정자가 이해할 수 있는 형태로 제공한다.

### 주요 기능

- 재고 관점과 계약 관점 분리
- 자재·공급사 영향 요약
- 대체 공급사 비교
- 예상 원가 영향 데이터
- 협상 포인트와 권장 조치
- 근거 문서 참조
- 대시보드·보고서 제공

### 책임

- Spring Boot: 결과 저장, 조회·집계 API, 보고서 파일 제공
- FastAPI: 브리핑 본문과 권장 조치 초안 생성

### Spring Boot 기능

- 브리핑 결과와 참조 근거 저장
- Dashboard·Risk·Briefing 조회 API
- 역할별 통계와 그래프용 데이터 집계
- PDF/Excel 내보내기

### FastAPI 기능

- ERP 분석과 RAG 분석 결과 결합
- 재고 관점·계약 관점 별도 작성
- 협상 포인트와 권장 조치 초안 생성
- 확정 근거·참고 정보·경고 분리

## F6. 개체 식별·Master Data 정규화

### 어떤 기능인가?

뉴스·ERP·계약서에서 서로 다르게 표기된 동일 자재나 공급사를 하나의 내부 ID로 연결하는 기능이다. 예를 들어 `협력사A`, `(주)에이컴퍼니`, `A Company`가 같은 공급사임을 확인하여 중복이나 잘못된 연결을 방지한다.

### 목표

서로 다른 데이터 소스의 자재·공급사 표현을 동일한 내부 ID에 정확하게 연결한다.

### 주요 기능

- 자재·공급사 Alias
- Vendor ID·사업자등록번호 우선 매칭
- 국가·항만 코드 표준화
- 자동 매칭 후보와 Confidence
- 최종 매칭 결과 저장

### 책임

- Spring Boot: Master Data와 최종 매칭 결과 관리
- FastAPI: 비정형 문서에서 개체명 추출과 유사 후보 제시

### Spring Boot 기능

- 자재·공급사 Master와 Alias 테이블 관리
- 식별자 기반 확정 매칭
- 중복 개체 방지와 최종 내부 ID 확정
- 분석 Context에 확정 ID 포함

### FastAPI 기능

- 뉴스·계약서에서 자재·공급사·국가·항만명 추출
- 명칭 유사도 또는 LLM 기반 매칭 후보 반환
- 확정되지 않은 후보를 임의로 Master에 저장하지 않음

## F7. 출처·근거·데이터 계보

### 어떤 기능인가?

AI의 결론이 어떤 뉴스, ERP 수치, 계약서 조항, 모델 및 규칙에서 만들어졌는지 거슬러 확인할 수 있게 하는 기능이다. 사용자가 결과를 그대로 믿는 대신 원본 근거를 직접 확인할 수 있게 한다.

### 목표

모든 분석 결과가 어떤 원문·ERP 수치·계약 조항·모델·규칙에서 만들어졌는지 추적할 수 있게 한다.

### 주요 기능

- 원본 URL과 수집 시각
- ERP Context Snapshot
- RAG 문서·페이지·조항
- 모델·규칙 버전과 reason_codes
- 확정 근거·참고 정보·경고 구분

### 책임

- Spring Boot: 원본과 분석 결과의 관계를 PostgreSQL에 보존하고 조회 API 제공
- FastAPI: 분석에 실제 사용한 근거와 버전을 응답에 포함

### Spring Boot 기능

- 원문·출처·수집 시각 저장
- ERP Snapshot과 분석 결과 연결
- FastAPI가 반환한 참조 근거 저장
- React가 근거를 조회할 API 제공

### FastAPI 기능

- RAG 문서·페이지·조항 반환
- `reason_codes`, `rule_version`, `model_version` 반환
- Mock/실제 분석 구분
- 근거 부족 경고 반환

## F8. 규제 Hard Gate·정책 유효성

### 어떤 기능인가?

FEOC·CRMA와 같이 위반 자체가 중대한 규제를 확인하고, 현재 유효한 규제를 위반한 경우 다른 조건이 양호하더라도 위험 등급을 강제로 심각으로 올리는 기능이다.

### 목표

현재 유효한 FEOC·CRMA 등의 규제 위반이 확인되면 다른 조건과 무관하게 Severity에 강제 반영한다.

### 주요 기능

- 규제 적용 지역·유효 기간·상태 관리
- 공급사 규제 상태 확인
- 유효한 규제만 분석에 적용
- 위반 시 CRITICAL Hard Gate
- 적용 규제와 근거 반환

### 책임

- Spring Boot: 규제와 공급사 사실 데이터 관리, 분석 시점 Context 구성
- FastAPI: 전달받은 규제 Context를 Severity Rule Engine에 적용

### Spring Boot 기능

- 규제 Metadata·버전·유효 기간 저장
- 공급사 FEOC 등 규제 상태 조회
- 분석 시점에 유효한 규제만 FastAPI에 전달

### FastAPI 기능

- 규제 Hard Gate 조건 평가
- CRITICAL 강제 격상
- 적용한 규제 코드와 reasonCode 반환

## F9. 공급사 자격·대체 공급사 추천

### 어떤 기능인가?

기존 공급사가 위험할 때 아무 공급사나 추천하지 않고, 품질 인증·규제 적합성·생산 능력·납기 조건을 충족한 실제 거래 가능 후보만 선별하여 비교하는 기능이다.

### 목표

실제 구매 후보가 될 수 있는 적격 공급사만 선별하고 상황별 장단점을 설명한다.

### 주요 기능

- IATF 16949·PPAP 확인
- FEOC·규제 적합성 확인
- 배터리급 자재 공급 가능 여부
- 생산 능력·MOQ·Lead Time·가격 비교
- 적격 후보 필터와 추천 근거

### 책임

- Spring Boot: 자격 사실 조회와 필수 조건 필터링
- FastAPI: 적격 후보 비교와 추천 사유 생성

### Spring Boot 기능

- 공급사 자격·인증·규제·납기·단가 조회
- 자격 미달 후보 제거
- 적격 후보 Context를 FastAPI에 전달

### FastAPI 기능

- 적격 후보 간 장단점 비교
- 현재 리스크 상황에 맞는 후보 우선순위 초안
- 추천 근거와 주의사항 반환
- 자격 미달 후보를 새로 생성하지 않음

## F10. 알림·에스컬레이션

### 어떤 기능인가?

주의 또는 심각한 위험이 발생했을 때 담당자가 놓치지 않도록 알림을 보내는 기능이다. 심각한 사건은 즉시 알리고 주의 사건은 일일 브리핑에 포함하는 방식으로 동작한다.

### 목표

리스크 등급에 따라 필요한 사용자에게 적절한 시점에 정보를 전달한다.

### 주요 기능

- Critical 즉시 알림
- Warning 일일 브리핑 포함
- 중복 알림 방지
- 발송 대상과 결과 관리

### 책임

- Spring Boot: 알림 정책 판단과 실제 발송
- FastAPI: Severity와 알림 판단 근거만 반환

### Spring Boot 기능

- Severity별 알림 정책
- 이메일 등 알림 채널 연동
- 중복 발송 방지와 발송 결과 기록

### FastAPI 기능

- Severity와 reason_codes 반환
- 이메일·Slack 등을 직접 호출하지 않음

## F11. 사용자 계층별 대시보드

### 어떤 기능인가?

같은 분석 결과를 구매팀에는 상세한 실행 정보로, 경영기획에는 집계와 추이로, 경영진에는 핵심 KPI로 보여주는 역할별 화면 지원 기능이다.

### 목표

구매팀·경영기획·경영진이 각 역할에 필요한 수준으로 리스크를 확인하게 한다.

### 주요 기능

- 구매팀 상세 리스크·재고·계약 정보
- 경영기획 사업부·공급사·지역별 통계
- 경영진 핵심 KPI와 Critical 요약

### 책임

- Spring Boot: 역할별 조회·집계 API와 접근 제어
- FastAPI: 별도의 사용자별 대시보드 기능 없음

### Spring Boot 기능

- 역할별 Dashboard API
- PostgreSQL 집계
- 접근 권한 적용

### FastAPI 기능

- 공통 분석 결과만 생성
- 사용자 역할별 화면 데이터는 생성하지 않음

## F12. 대응 조치·업무 추적

### 어떤 기능인가?

AI가 제안한 권장 조치를 담당자·기한·진행 상태가 있는 실제 업무로 바꾸고 완료까지 추적하는 기능이다. 현재 프로젝트에서는 부가적인 업무관리 범위로 판단하여 구현하지 않는다.

### 상태

MVP와 현재 백엔드 범위에서 삭제한다.

### 대체 범위

- FastAPI는 브리핑에 `recommendedActions` 초안만 반환한다.
- Spring Boot는 별도의 담당자·기한·승인·완료 Task 기능을 구현하지 않는다.

M1~M6은 원칙적으로 Spring Boot 기능이 아니다. 현재 범위에서는 모델링 팀이 로컬에서 수행하고, FastAPI는 최종 Artifact를 받아 추론만 수행한다.

## M1. 사람 검증 라벨링

### 어떤 기능인가?

LLM이 임시로 붙인 사건 분류 라벨을 사람이 직접 검토하여 모델이 학습할 최종 정답을 만드는 작업이다. 백엔드 기능이 아니라 모델링 팀의 로컬 작업이다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 목표·주요 기능

- LLM 초안 라벨을 사람이 검증
- 최종 학습 라벨 확정

### 책임

- 모델링 팀: 전체 수행
- Spring Boot: 기능 없음
- FastAPI: 기능 없음

## M2. Dataset·Feature 버전 관리

### 어떤 기능인가?

모델이 어떤 기간의 데이터와 어떤 입력 변수로 학습되었는지 기록하여 동일한 학습 결과를 다시 만들 수 있게 하는 모델링 관리 작업이다. 백엔드에서는 구현하지 않는다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 목표·주요 기능

- 학습 Dataset과 Feature 정의 재현
- 학습 데이터 설명 자료 관리

### 책임

- 모델링 팀: 전체 수행
- Spring Boot: 기능 없음
- FastAPI: 전달받은 `feature_schema.json`만 사용

## M3. Model Registry

### 어떤 기능인가?

여러 모델 버전의 성능·승인·배포 상태를 관리하는 기능이다. 현재는 완전한 관리 시스템을 만들지 않고 FastAPI가 최종 모델 파일과 입력 규격을 읽는 기능만 유지한다.

### 상태

완전한 Registry는 삭제하고 Artifact 로딩 규격만 유지한다.

### 목표

FastAPI가 전달받은 최종 모델과 입력·출력 규격을 정확하게 로드한다.

### 주요 기능

- 모델 파일
- Feature Schema
- Class Mapping
- 최소 Model Metadata

### 책임

- 모델링 팀: 최종 Artifact 생성·전달
- Spring Boot: 기능 없음
- FastAPI: Artifact 로딩과 추론

### Spring Boot 기능

- 없음

### FastAPI 기능

- `ml/artifacts`에서 모델 로드
- Feature 이름·순서 검증
- Class Mapping 적용
- 모델 로딩 Health 상태 제공

## M4. 시계열 학습 검증

### 어떤 기능인가?

과거 데이터로 학습하고 이후 시점 데이터로 평가하여 미래 정보 유출이나 같은 사건의 중복 기사로 성능이 과장되지 않도록 검증하는 모델링 작업이다.

### 상태

백엔드 구현 범위에서 삭제한다.

### 책임

- 모델링 팀: TimeSeriesSplit, 데이터 누수 방지, 성능 평가
- Spring Boot: 기능 없음
- FastAPI: 기능 없음

## M5. 설명 가능성

### 어떤 기능인가?

모델이 왜 특정 사건을 해당 유형이나 등급으로 판단했는지 Confidence, 주요 입력값, 규칙 근거 등으로 설명하는 기능이다. 현재 백엔드는 기본 근거만 전달하고 상세 SHAP 검증은 모델링 팀이 수행한다.

### 상태

실시간 SHAP 기능은 삭제하고 기본 분석 근거만 유지한다.

### 목표

백엔드는 사용자가 결과를 이해하는 데 필요한 최소 근거를 전달한다.

### 주요 기능

- Classification Confidence
- Severity reason_codes와 계산 값
- ERP 수치와 RAG 참조 근거

### 책임

- 모델링 팀: 로컬 SHAP 검증
- Spring Boot: 근거 저장·조회
- FastAPI: 추론 결과에 기본 근거 필드 포함

### Spring Boot 기능

- FastAPI 근거 저장과 조회 API 제공

### FastAPI 기능

- Confidence, reason_codes, calculation_details 반환
- 실시간 SHAP 계산은 수행하지 않음

## M6. 모델·데이터 모니터링

### 어떤 기능인가?

운영 중 입력 데이터의 분포나 모델 성능이 학습 당시와 달라지는지 장기간 감시하는 기능이다. 현재 프로젝트는 장기 운영을 목표로 하지 않으므로 구현하지 않는다.

### 상태

구현하지 않는다.

### 대체 범위

- FastAPI Health Check에서 모델 파일 로딩 성공 여부만 확인한다.
- Drift·성능 추적·자동 Rollback은 구현하지 않는다.