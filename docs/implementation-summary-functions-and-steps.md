# 백엔드·AI 파이프라인 구현 결과 정리

## 1. 전체 구현 요약

이번 구현에서는 `C1 파일 업로드`를 중심으로 문서를 안전하게 접수하고, PostgreSQL에 처리 상태를 저장한 뒤, FastAPI에서 문서를 추출·청킹하고 Mock Embedding으로 ChromaDB에 적재·검색하는 흐름을 완성했다.

```text
React 검증 화면
→ Spring Boot 문서 API
→ 원본 파일 Volume 저장
→ PostgreSQL Metadata·상태 저장
→ FastAPI 문서 추출·청킹
→ Mock Embedding
→ ChromaDB 적재·검색
→ Spring Boot 검색 결과 반환
```

추가로 다음 기반도 구현되어 있다.

- `F6 최소 Master Data`: 자재·공급사·계약 내부 ID와 관계 Schema
- `C2 인증 연계`: JWT Bearer Token으로 Spring 외부 API 보호
- `F1 Spring 영역`: ERP Mock Data 조회와 재고·의존도·공급 공백 계산
- `Severity Rule Engine`: Spring 요청·저장과 FastAPI 규칙 계산

실제 LLM, XGBoost, OpenAI Embedding은 아직 모델 또는 API 연동 조건이 준비되지 않았으므로 Mock·규칙 기반 구현을 사용한다.

> **ERP 데이터에 대한 예외**: Embedding·LLM·XGBoost는 실제 모델이 준비되면 Adapter만 교체하는 것을 전제로 한다(S16). 반면 **ERP Mock 데이터(`data/ERP_data/spring-csv`)는 회사 보안 정책상 실제 사내 ERP 시스템에 직접 연결할 수 없어, 실제 ERP 연동으로 교체할 계획이 없는 이 프로젝트의 최종 데이터 소스**다. 응답의 `data_source: "ERP_MOCK"`, `mock: true` 필드는 "실시간 ERP 피드가 아님"을 나타낼 뿐, S16 이후 교체될 임시 표시가 아니다. 이 CSV 데이터를 갱신·관리하는 기능(예: 재적재 트리거, 업로드)은 일회성 개발 편의가 아니라 영구적으로 유지할 운영 기능으로 설계해야 한다.

## 2. 기능별 구현 결과

| 기능 | 이번에 구현된 범위 | 판정 |
| --- | --- | --- |
| C1 파일 업로드 | 파일 검증, 원본 저장, PostgreSQL 상태 저장, FastAPI 처리, Chroma 적재, 상태 조회, 재처리 | Mock Embedding 기준 완료 |
| F2 RAG 기반 | 조항·문단 청킹, ChromaDB, Metadata Filter, 근거 청크 검색 | 검색 기반 완료, 대응 보고서 미구현 |
| F6 Master Data | 자재·공급사·계약·문서 Schema, Seed, ERP 외부 ID와 내부 PK 관리 | 최소 범위 완료 |
| C2 인증 | 회원가입·로그인·JWT·Refresh·Logout, PostgreSQL 사용자·폐기 토큰 저장 | 통합 완료 |
| C3 최소 상태 | `PENDING → PROCESSING → COMPLETED/FAILED` | 완료 |
| C5/D1 중복 방지 | 계약별 SHA-256 중복 방지, Chroma 재적재 시 기존 청크 교체 | 완료 |
| C6 관측성 | PostgreSQL·Spring Health 및 Chroma Health 기반 | 최소 범위 완료 |
| M5 기본 근거 | 검색 유사도, 페이지·청크·Embedding 버전 반환 | 최소 범위 완료 |
| F1 ERP 영향 | ERP Context 조회, 가용재고·재고일수·안전재고·의존도·공급 공백 계산 | Spring 영역 완료 |
| F3/F8 Severity | 규칙 기반 NORMAL/WARNING/CRITICAL/UNKNOWN 및 FEOC Hard Gate | 규칙 엔진 구현 |
| React 업로드 | 백엔드 검증용 업로드·상태·중복·오류 화면 | 코드·자동검증 완료, 정식 React 병합 대기 |

## 2-1. C1 업로드 UX·데이터 형식 개선

| 항목 | 내용 | 근거 | 반영 위치 | 상태 |
| --- | --- | --- | --- | --- |
| 1. document_id 접두어 | 문서 유형을 사람이 오분류할 가능성이 없으므로, `{prefix}_{UUID}` 형식으로 발급 (계약서 `con_`, 발주서 `po_`, 사양서 `spc_`, 인증서 `cer_`, 기타 `doc_`) | - | `Document.java`, `DocumentService.java`, `V1__create_master_and_document_schema.sql` | 완료 |
| 2. 문서 종류 드롭다운 | 업로드 UI에 문서 종류 선택 추가 (계약서/발주서/사양서/인증서/기타) | - | `App.jsx` | 완료 |
| 3. 파일 크기 50MB 제한 | 업로드 가능한 최대 파일 크기를 10MB → 50MB로 상향 | 코칭 시간 피드백 반영 | `documentApi.js`, `application.yml`, `DocumentService.java` | 완료 |
| 4. CSV 업로드 지원 | 기존 PDF/TXT에 CSV 확장자·MIME(`text/csv`) 허용 추가, FastAPI에서 TXT와 동일하게 UTF-8 텍스트로 추출 | - | `documentApi.js`, `DocumentService.java`, `document_service.py` | 완료 |

### 완료라는 표현의 범위

`C1 완료`는 현재 계획한 Mock Embedding 기반 범위의 완료를 의미한다. 실제 OpenAI Embedding과 LLM 계약 해석까지 완료했다는 의미는 아니다.

`F1 Spring 영역 완료`는 ERP 수치 조회와 결정적 계산이 완료됐다는 의미다. 사건과 ERP Context를 결합한 FastAPI 영향 설명, 권장 조치, Context Snapshot 영구 저장은 남아 있다.

## 3. 실제 호출 흐름

### 3.1 문서 업로드

```text
1. React 또는 Swagger에서 PDF/TXT 선택
2. Spring POST /api/v1/documents 호출
3. Spring이 확장자·MIME·크기·ID·Hash 검증
4. Spring이 UUID document_id 발급
5. uploads/contracts/{document_id}/original.* 저장
6. PostgreSQL contract_documents에 PENDING 저장
7. 상태를 PROCESSING으로 변경
8. FastAPI POST /api/v1/documents/process 호출
9. FastAPI가 PDF/TXT 텍스트 추출
10. 조항 → 문단 → 길이 기준 순서로 청킹
11. Mock Embedding 생성
12. ChromaDB에 청크와 Metadata 저장
13. FastAPI가 청크 수와 Embedding 정보를 반환
14. Spring이 PostgreSQL 상태를 COMPLETED로 변경
15. 오류 발생 시 FAILED와 오류 코드를 저장
```

### 3.2 문서 검색

```text
React 또는 Swagger
→ Spring POST /api/v1/rag/search
→ contract_id 또는 supplier_id 필수 검증
→ FastAPI POST /api/v1/rag/search
→ Chroma Metadata Hard Filter
→ 필터 범위 안에서 Vector 검색
→ 원문·페이지·청크·유사도 반환
→ Spring이 외부 응답으로 전달
```

Spring Boot는 ChromaDB에 직접 접근하지 않고, React는 FastAPI를 직접 호출하지 않는다.

### 3.3 ERP 영향 계산

```text
사용자 또는 내부 서비스
→ Spring POST /api/v1/erp/context
→ ERP 자재 ID를 내부 material_id로 매핑
→ 재고·사용량·계약·공급사·발주 조회
→ 가용재고·재고일수·공급 공백 계산
→ ERP Context 반환
```

## 4. 핵심 코드와 함수

### 4.1 C1 Spring Boot

#### `DocumentController.java`

| 함수 | 역할 |
| --- | --- |
| `upload()` | `POST /api/v1/documents` 요청을 받고 서비스에 전달 |
| `get()` | `GET /api/v1/documents/{document_id}`로 PostgreSQL 상태 조회 |
| `reprocess()` | 저장된 원본을 같은 ID로 ChromaDB에 다시 적재 |

#### `DocumentService.java`

| 함수 | 동작 |
| --- | --- |
| `upload()` | 검증 → Hash → 중복 확인 → 원본 저장 → PENDING/PROCESSING → FastAPI 호출 → COMPLETED/FAILED 전체 흐름 관리 |
| `validate()` | 파일, MIME, 크기, 문서 유형, 계약·공급사·자재 존재 여부 검사 |
| `sanitizeFileName()` | `../` 같은 경로 문자열을 제거하고 안전한 파일명만 사용 |
| `sha256()` | 문서 내용을 Hash로 바꿔 동일 계약의 중복 문서 판단 |
| `processWithFastApi()` | Spring이 만든 `document_id`와 파일·Metadata를 FastAPI로 전달하고 응답 ID 일치 확인 |
| `get()` | UUID 형식을 검사하고 PostgreSQL에서 문서 상태 조회 |
| `reprocess()` | Volume의 원본을 다시 읽어 `force_reprocess=true`로 적재 |

#### `Document.java`

| 함수 | 동작 |
| --- | --- |
| `pending()` | 최초 Metadata를 `PENDING` 상태로 생성 |
| `markProcessing()` | FastAPI 처리가 시작됐음을 기록 |
| `markCompleted()` | 청크 수·Embedding 종류·버전과 완료 시각 기록 |
| `markFailed()` | 오류 코드·메시지와 실패 상태 기록 |

#### `DocumentRepository.java`

PostgreSQL `contract_documents` CRUD, 계약별 Hash 중복 조회, 계약·공급사 조합과 자재 존재 여부 검증을 담당한다.

### 4.2 C1·F2 FastAPI

#### `documents.py`

`process_document()`가 Spring의 Multipart 요청을 받아 `DocumentService.process()`를 실행하고 공통 응답 Schema로 반환한다.

#### `document_service.py`

| 함수 | 동작 |
| --- | --- |
| `process()` | Hash 계산 → 추출 → 청킹 → 청크 생성 → Vector Store 적재 |
| `_extract_pages()` | TXT UTF-8 디코딩 또는 PDF 페이지별 텍스트 추출과 오류 구분 |
| `_chunk_pages()` | 페이지 번호를 유지하며 각 페이지를 청킹 |
| `_split_clause_sections()` | `제1조`, `Article 1` 같은 조항 경계를 우선 탐색 |
| `_split_section()` | 긴 조항을 문단 단위로 분리 |
| `_split_long_text()` | 긴 문단만 크기와 overlap 기준으로 분리 |

`InMemoryDocumentStore`도 남아 있지만 ChromaDB가 연결된 실제 경로에서는 Vector 검색의 영속 저장소로 사용하지 않는다. 이 저장소는 처리 중 중복 보조와 단위 테스트 대체 구현에 가깝다.

#### `embedding_service.py`

| 함수 | 동작 |
| --- | --- |
| `embed_documents()` | 여러 청크를 Vector로 변환 |
| `embed_query()` | 검색어를 같은 규격의 Vector로 변환 |
| `_embed()` | 토큰 분리 → SHA-256 위치 계산 → 빈도 누적 → 정규화 |
| `get_embedding_provider()` | `EMBEDDING_PROVIDER=mock/openai` 설정에 따라 구현 선택 |

현재 기본값은 결정론적 `MOCK_TOKEN_HASH`, 버전은 `mock-v1`이다.

#### `vector_store_service.py`

| 함수 | 동작 |
| --- | --- |
| `health_check()` | ChromaDB와 Collection 접근 확인 |
| `upsert_chunks()` | 필수 필드 검증, 같은 문서 기존 청크 삭제, 새 청크 저장 |
| `search()` | Metadata Filter를 먼저 적용하고 Vector 유사도 검색 |
| `get_document()` | 특정 문서의 전체 청크 조회 |
| `delete_document()` | 특정 `document_id`의 청크 삭제 |
| `_vector_id()` | `{document_id}:{chunk_index}` 형식 ID 생성 |
| `_cosine_similarity()` | Chroma 거리 값을 화면용 유사도 점수로 변환 |

#### `rag_service.py`

`search()`가 Chroma 검색 결과를 문서·계약·공급사·자재 ID, 페이지, 청크, 원문, Hash, 유사도, Embedding 정보가 포함된 응답으로 변환한다.

### 4.3 F6 Master Data

#### `V1__create_master_and_document_schema.sql`

다음 테이블과 관계를 만든다.

```text
materials
suppliers
contracts
contract_documents
```

PK, FK, Unique, 상태 Check Constraint와 조회 Index를 통해 잘못된 참조와 중복을 DB에서도 차단한다.

#### `R__insert_c1_reference_seed.sql`

C1 업로드·검색 E2E에 사용할 최소 자재·공급사·계약 데이터를 반복 적용 가능한 Seed로 넣는다.

#### `V4__extend_erp_master_and_operations.sql`

F6을 ERP Mock Data까지 확장해 다음 구조를 추가한다.

```text
warehouses
supplier_materials
inventory_snapshots
material_consumptions
purchase_orders
purchase_order_items
goods_receipts
```

ERP 문자열 ID와 PostgreSQL 내부 숫자 PK를 분리한다.

### 4.4 F1 Spring Boot

#### `ErpController.java`

`context()`가 `POST /api/v1/erp/context` 요청을 받고 `ErpService.buildContext()` 결과를 반환한다.

#### `ErpService.java`

`buildContext()`의 처리 순서는 다음과 같다.

```text
자재 매칭
→ 현재 재고 조회
→ 평균 일 사용량 조회
→ 공급사·계약 조회
→ 가용재고 계산
→ 재고일수·안전재고일수 계산
→ 다음 입고일 조회
→ 예상 공급 공백 계산
→ 대체 공급사 상태 조회
→ 데이터 품질 상태와 ERP Context 반환
```

주요 계산식:

```text
available_quantity
= on_hand_quantity
- reserved_quantity
- blocked_quantity
- quality_hold_quantity

inventory_days
= available_quantity / average_daily_usage

safety_stock_shortage_quantity
= max(safety_stock_quantity - available_quantity, 0)

expected_supply_gap_days
= max(next_eta_days - inventory_days, 0)
```

#### `ErpRepository.java`

| 함수 | 조회 내용 |
| --- | --- |
| `findMaterial()` | ERP 자재 ID와 내부 ID 매핑 |
| `aggregateCurrentInventory()` | 최신 재고와 안전재고 집계 |
| `aggregateCurrentConsumption()` | 평균 일 사용량 조회 |
| `findSupply()` | 공급사·계약·의존도·FEOC 조회 |
| `findNextInbound()` | 가장 빠른 유효 입고 예정일 조회 |
| `sumRemainingQuantity()` | 발주량에서 입고량을 뺀 미입고 수량 계산 |
| `findAlternativeSupplierStatus()` | 다른 승인 공급사 존재 여부 확인 |

### 4.5 React 검증 화면

#### `frontend/src/App.jsx`

| 함수 | 동작 |
| --- | --- |
| `submit()` | 파일 검증, 토큰 확인, 업로드, 진행 상태와 결과 표시 |
| `loadStatus()` | 문서 상태 조회 및 처리 중일 때 1.5초 간격 재조회 |
| `applyDocument()` | 응답을 화면에 반영하고 마지막 문서 ID 저장 |

#### `frontend/src/documentApi.js`

| 함수 | 동작 |
| --- | --- |
| `validateDocumentFile()` | PDF/TXT, MIME, 빈 파일, 10MB 제한 검사 |
| `uploadDocument()` | Bearer Token을 포함해 Spring Multipart API 호출 및 진행률 제공 |
| `getDocumentStatus()` | Spring 상태 조회 API 호출 |

React 검증 화면에는 FastAPI URL이 없으며 모든 요청이 Spring Boot를 통과한다.

## 5. 1~8단계 개발 과정

1~8단계는 서로 독립된 사용자 기능 번호가 아니라 C1과 RAG 기반을 안전하게 완성하기 위해 적용한 개발 순서다.

| 단계 | 관련 기능 | 구현 내용 | 현재 상태 |
| --- | --- | --- | --- |
| 1. PostgreSQL 기본 환경 | 공통 기반, C6 일부 | Docker, DataSource, JPA, Flyway, Health | 완료 |
| 2. 최소 Master Data·문서 Schema | F6 + C1 | 자재·공급사·계약·문서 테이블과 Seed | 완료 |
| 3. C1-A 영구 저장 | C1 + C3 + C5/D1 | 원본 Volume, Metadata, 상태, Hash 중복 방지 | 완료 |
| 4. 추출·청킹 | C1 + F2 | PDF/TXT, 페이지, 조항·문단 우선 청킹 | 완료 |
| 5. ChromaDB | F2 + C5/C6 | Collection, 저장·조회·삭제·재적재 | 완료 |
| 6. Mock Embedding | F2 + M5 일부 | 토큰 Hash Vector, Metadata Filter, 유사도 | 완료 |
| 7. C1-B 실제 E2E | C1 + F2 | Spring·PostgreSQL·FastAPI·Chroma 업로드·검색 | 완료 |
| 8. React 업로드 | C1 UI + C2 연계 | 업로드, 상태, 중복, 오류, 새로고침 복원 | 코드·자동검증 완료 |

### 8단계 완료 상태를 정확히 표현하는 방법

현재 저장소의 `frontend/`는 백엔드 E2E 검증용 React 클라이언트다. 코드, 파일 검증 테스트, production build, Spring 인증·문서 Controller 테스트는 통과했다.

다만 프론트엔드 팀의 정식 React 프로젝트와 병합하지 않았으므로 다음처럼 표현하는 것이 정확하다.

```text
1~7단계
→ 완료

8단계 검증용 React
→ 코드와 자동 테스트 완료

정식 React 프로젝트 연결과 브라우저 수동 E2E
→ 대기
```

## 6. 현재 코드에 추가로 구현된 범위

로컬 코드에는 1~8단계 이후 작업도 일부 구현되어 있다.

| 후속 단계 | 현재 코드 상태 |
| --- | --- |
| 9단계 ERP Mock Data | V4 ERP Schema와 ERP 조회 구조 구현 |
| 10단계 F1 ERP 영향 계산 | Spring `ErpService`와 Context API 구현 |
| 11단계 Severity Rule Engine | Spring 요청·저장 API와 FastAPI 규칙 엔진 구현 |
| 12단계 RAG Mock 대응 분석 | 근거 검색까지 구현, 템플릿 대응 보고서 미구현 |
| 13단계 템플릿 브리핑 | 기존 FastAPI Mock 기반 일부가 있으나 ERP·RAG 통합 저장 흐름 미완료 |
| 14단계 전체 조회·React | 실시간 알림 Schema 일부와 문서 화면만 존재, 전체 대시보드 미완료 |
| 15단계 전체 Docker 통합 | PostgreSQL·Chroma 중심 구성, 5개 서비스 통합·전체 장애 검증 미완료 |
| 16단계 실제 모델 교체 | Adapter 계약 일부 존재, 실제 모델 연결 보류 |

## 7. 남은 작업

### 바로 확인할 작업

- 프론트엔드 팀의 정식 React 코드와 업로드 화면 병합
- 실제 로그인 화면이 저장하는 Token Key와 `access_token` 통일
- 실제 브라우저에서 정상 PDF/TXT 업로드
- 중복·실패·새로고침 복원 화면 확인

### F1 완성을 위한 작업

- 분석 시점 ERP Context Snapshot 영구 저장
- 사건·뉴스와 ERP Context 연결
- Spring에서 FastAPI 영향 해석 API 호출
- FastAPI 템플릿 기반 영향 설명과 권장 확인 사항 생성
- 분석 결과와 근거 PostgreSQL 저장·조회

### F2 완성을 위한 작업

- RAG 검색 근거를 사용한 템플릿 대응 분석
- 적격 대체 공급사 Context 연결
- 근거 부족과 담당자 검토 필요 상태 표시
- 계약 대응 결과 저장·조회

### 이후 업무 기능

- F5 템플릿 브리핑과 PostgreSQL 저장
- Dashboard·Risk·Contract·Briefing 전체 조회 API
- 정식 React 역할별 대시보드
- 전체 Docker Compose와 장애 시나리오 검증
- 실제 XGBoost·LLM·Embedding Adapter 교체

## 8. 조원에게 설명할 때 사용할 요약

> 이번에는 C1 파일 업로드를 중심으로 Spring의 파일 검증·원본 저장·PostgreSQL 상태 관리와 FastAPI의 문서 추출·청킹·Mock Embedding·ChromaDB 검색을 연결했습니다. Spring이 문서 ID와 업무 데이터를 관리하고 FastAPI가 문서와 벡터 처리를 담당하도록 책임을 분리했습니다. 이 과정에서 F6 최소 Master Data와 C2 인증을 함께 연결했고, 현재 로컬 코드에는 다음 단계인 F1 ERP 영향 수치 계산과 Severity Rule Engine까지 구현되어 있습니다. 실제 LLM·XGBoost·OpenAI Embedding은 아직 준비되지 않아 Mock과 규칙 기반으로 교체 가능한 구조를 사용하고 있습니다. 1~7단계는 실제 E2E까지 완료됐고, 8단계는 검증용 React 코드와 자동 테스트까지 완료됐으며 정식 프론트엔드 프로젝트 병합과 브라우저 수동 검증이 남아 있습니다.
