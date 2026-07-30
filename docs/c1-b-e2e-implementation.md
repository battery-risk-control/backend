# 7단계 C1-B 업로드·적재·검색 실제 E2E 구현

## 구현 결과

```text
C1 파일 업로드: 완료
RAG 적재 기반: 완료
Mock Embedding 검색 E2E: 완료
실제 Embedding·LLM: 보류
```

## 전체 흐름

```text
Spring Multipart 업로드
→ 원본 Volume 저장
→ PostgreSQL PENDING
→ PostgreSQL PROCESSING
→ FastAPI /api/v1/documents/process
→ 텍스트 추출·청킹
→ Mock Embedding
→ ChromaDB clean upsert
→ PostgreSQL COMPLETED
→ Spring /api/v1/rag/search
→ FastAPI Metadata Filter 검색
→ 근거 청크 반환
```

## 새 Spring 외부 검색 API

```http
POST /api/v1/rag/search
```

구현 파일:

- `controller/RagController.java`
- `dto/RagDto.java`
- `service/RagService.java`

Spring은 `contract_id` 또는 `supplier_id`가 없는 검색을 `RAG_FILTER_REQUIRED`로 거절한다. 정상 요청은 FastAPI의 동일한 내부 경로로 전달하고 Chroma 검색 결과를 공통 Spring 응답 Envelope로 반환한다.

## 같은 문서 재처리

```http
POST /api/v1/documents/{document_id}/reprocess
```

처리 순서:

```text
PostgreSQL에서 문서 조회
→ Volume의 원본 파일 읽기
→ PROCESSING
→ FastAPI에 force_reprocess=true 전달
→ 같은 document_id의 기존 Chroma 청크 삭제
→ 새 청크 upsert
→ COMPLETED
```

일반 중복 업로드는 기존 문서를 반환하고 `duplicate=true`로 표시한다. `DUPLICATE`를 PostgreSQL 처리 상태로 저장하지 않는다.

## Chroma 부분 적재 정리

`ChromaVectorStore.upsert_chunks()`는 Chroma upsert 중 오류가 발생하면 같은 `document_id`로 저장된 청크를 다시 삭제한다. 정리가 끝나면 `VECTOR_STORE_FAILED`를 반환하고 Spring은 PostgreSQL 문서를 `FAILED`로 변경한다.

## 오류 전파

Spring은 FastAPI 공통 오류 본문의 `code`, `message`, HTTP 상태를 보존한다.

```text
FastAPI 중단
→ HTTP 503 FASTAPI_UNAVAILABLE
→ PostgreSQL FAILED / FASTAPI_UNAVAILABLE

Chroma 중단
→ HTTP 503 VECTOR_STORE_UNAVAILABLE
→ PostgreSQL FAILED / VECTOR_STORE_UNAVAILABLE
```

검색 중 Chroma 장애는 HTTP 503 검색 오류만 반환하고 기존 `COMPLETED` 문서를 변경하지 않는다.

## 실제 검증 결과

### 정상 TXT

```text
Spring 업로드 HTTP 200
PostgreSQL COMPLETED
PostgreSQL chunk_count=2
Chroma 청크 수=2
embedding_type=MOCK_TOKEN_HASH
embedding_version=mock-v1
```

Chroma ID:

```text
76bac6fd-fafe-4d79-8cb1-d6fc41f421c9:0
76bac6fd-fafe-4d79-8cb1-d6fc41f421c9:1
```

### 정상 PDF

```text
Spring 업로드 HTTP 200
PDF 청크 수=2
Chroma page_number=[1, 2]
```

### 검색

```text
contract_id Filter: 성공
supplier_id + material_id Filter: 성공
다른 supplier_id: 결과 0개
필터 없음: HTTP 422 RAG_FILTER_REQUIRED
실제 similarity_score: 0.566138
```

### 중복·재적재

```text
동일 파일 재업로드: 기존 document_id 반환, duplicate=true
같은 document_id 재처리: duplicate=false
재처리 전후 Chroma 청크 수=2
중복 Chroma ID 없음
```

### 입력 오류

```text
빈 파일: EMPTY_FILE
공백 문서: EMPTY_DOCUMENT
MIME 불일치: INVALID_MIME_TYPE
허용되지 않은 확장자: UNSUPPORTED_DOCUMENT_TYPE
손상 PDF: INVALID_PDF
비 UTF-8 TXT: TEXT_EXTRACTION_FAILED
존재하지 않는 계약·공급사: CONTRACT_SUPPLIER_NOT_FOUND
```

### 장애

```text
FastAPI 중단 업로드: HTTP 503 / FASTAPI_UNAVAILABLE
ChromaDB 중단 업로드: HTTP 503 / VECTOR_STORE_UNAVAILABLE
ChromaDB 중단 검색: HTTP 503 / VECTOR_STORE_UNAVAILABLE
검색 장애 후 기존 문서: COMPLETED 유지
부분 upsert 실패: 저장된 일부 청크 정리 확인
```

### 영속성

```text
ChromaDB 재시작 후 기존 문서 검색 성공
PostgreSQL 재시작 후 사용자 로그인 성공
PostgreSQL 재시작 후 문서 COMPLETED·chunk_count·Embedding 정보 유지
```

## 자동 테스트

```text
FastAPI: 61 passed
Spring Boot: BUILD SUCCESSFUL
```

FastAPI 테스트는 정상 PDF/TXT, 페이지 번호, 청킹 오류, 재적재, Chroma 부분 실패 정리, Metadata Filter와 연결 장애를 포함한다.

Spring 테스트는 외부 검색 API, snake_case 계약, 필터 검증, FastAPI 오류 보존, 원본 재처리, 잘못된 Master Data와 PostgreSQL 상태 전환을 포함한다.

## 실행 시 주의사항

- React는 Spring Boot만 호출한다.
- ChromaDB는 FastAPI만 접근한다.
- PostgreSQL과 ChromaDB는 하나의 트랜잭션으로 묶지 않는다.
- 문서 상태를 통해 두 저장소의 처리 결과를 관리한다.
- Mock과 실제 Embedding은 서로 다른 Collection을 사용한다.
- 실제 Embedding을 연결할 때 기존 Mock 문서를 새 Collection에 재임베딩해야 한다.
