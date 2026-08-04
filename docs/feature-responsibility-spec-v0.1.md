# 기능별 Spring Boot·FastAPI 책임 정의서 v0.1

## 1. 공통 책임 원칙

```text
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

# 2. F — 업무 기능

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

# 3. C — 공통 기반 기능

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

# 4. M — 모델링 기능

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

# 5. D — 데이터 처리 기능

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

# 6. 최종 담당 요약

| 기능 | Spring Boot | FastAPI | 모델링 팀 | 상태 |
| --- | --- | --- | --- | --- |
| F1 ERP 영향 | ERP Context·저장 | 영향 해석 | - | 유지 |
| F2 RAG | 업로드·Metadata | ChromaDB·검색·답변 | - | 유지 |
| F3 AI 분석 | 요청·저장 | Extraction·XGBoost·Severity | Artifact 제공 | 유지 |
| F4 수집 | Scheduler·수집·저장 | AI 전처리 | - | 유지 |
| F5 브리핑 | 저장·조회·보고서 | 초안 생성 | - | 유지 |
| F6 Master Data | 최종 ID 관리 | 개체 후보 추출 | - | 유지 |
| F7 근거 계보 | 저장·조회 | 근거 생성·반환 | - | 유지 |
| F8 규제 | 유효 규제 Context | Hard Gate | - | 유지 |
| F9 대체 공급사 | 자격 필터 | 후보 비교 설명 | - | 유지 |
| F10 알림 | 정책·발송 | Severity 반환 | - | 축소 유지 |
| F11 대시보드 | 조회·집계 API | - | - | 유지 |
| F12 업무 추적 | - | - | - | 삭제 |
| C1 업로드 | 접수·관리 | 문서 처리 | - | 유지 |
| C2 인증 | 전담 | 내부 호출만 | - | 유지 |
| C3 작업·재시도 | 최소 상태 | 오류 응답 | - | 축소 유지 |
| C4 감사 로그 | 기본 로그만 | 기본 로그만 | - | 독립 기능 삭제 |
| C5 중복 방지 | 시스템 중복 방지 | RAG 중복 방지 | - | 유지 |
| C6 관측성 | Spring 측 Health | AI 측 Health | - | 축소 유지 |
| C7 LLM 검증 | 응답 계약 확인 | 프롬프트·출력 검증 | - | 축소 유지 |
| M1 라벨링 | - | - | 담당 | 백엔드 제외 |
| M2 Dataset | - | Schema 사용 | 담당 | 백엔드 제외 |
| M3 Registry | - | Artifact 로딩 | Artifact 제공 | Registry 삭제 |
| M4 학습 검증 | - | - | 담당 | 백엔드 제외 |
| M5 설명 가능성 | 근거 저장 | 기본 근거 반환 | 로컬 SHAP | 축소 유지 |
| M6 모니터링 | - | 로딩 Health만 | - | 삭제 |
| D1 중복 제거 | 전담 | 문서 ID 보조 | - | 축소 유지 |
| D2 소스 신뢰도 | 출처만 저장 | 판정 안 함 | - | 삭제 |
| D3 품질 검증 | 기본 DTO·DB 검증 | Pydantic 검증 | 로컬 데이터 검증 | 독립 기능 삭제 |
| D4 단위·통화 | 표준 Context | 표준값 사용 | - | 변환 기능 삭제 |
