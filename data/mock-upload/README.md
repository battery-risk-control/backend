# 문서 업로드 테스트용 Mock 파일

`POST /api/v1/documents` (Spring) → `POST /api/v1/documents/process` (FastAPI) 경로를
txt / pdf / csv 세 확장자로 모두 태워보기 위한 mock 세트다.
내용은 `data/ERP_data/spring-csv`의 시드 ERP 데이터, `data/RAG_DATA/erp_aligned`의
계약서 코퍼스와 ID·계약번호·단가까지 정합하게 맞췄다.

| 파일 | 확장자 | Content-Type | 대상 계약 | 확인 목적 |
|---|---|---|---|---|
| `CTR-001_mock_supply_agreement.txt` | txt | `text/plain` | CTR-001 / SUP-CHL-01 / MAT-LI-CARB | 한국어 `제 N 조` 조항 경계 청킹 |
| `CTR-002_mock_supply_agreement.pdf` | pdf | `application/pdf` | CTR-002 / SUP-AUS-01 / MAT-LI-CARB | pypdf 2페이지 추출 + 영문 `Article N` 경계 청킹 |
| `CTR-001_mock_delivery_schedule.csv` | csv | `text/csv` | CTR-001 / SUP-CHL-01 / MAT-LI-CARB | csv 확장자 허용 + MIME 교차검증 통과 |

`make_mock_pdf.py`는 PDF를 재생성하는 스크립트다 (외부 의존성 없음, 검증에만 pypdf 사용).

## CSV에 대한 주의

업로드 파이프라인은 **CSV를 표로 파싱하지 않는다.** `document_service._extract_pages`는
`.txt`와 `.csv`를 동일하게 UTF-8 평문으로 읽어 그대로 청킹·임베딩한다
(`fastapi-ai/app/services/document_service.py:199`). 따라서 이 CSV는 ChromaDB에
텍스트 1청크로 들어갈 뿐, ERP 테이블(`materials`, `contracts`, `purchase_orders` …)에는
한 행도 적재되지 않는다.

ERP 테이블 적재는 업로드와 완전히 별개의 경로이며, 총 세 가지뿐이다:
- Flyway `R__insert_c1_reference_seed.sql` (기동 시 항상, C1 참조용 3행)
- `ErpSeedConfig` (기동 시 `ERP_SEED_ENABLED=true`, `data/ERP_data/spring-csv` 일괄 적재)
- `POST /api/v1/erp/admin/**` 10종 (JSON 단건 upsert)

`POST /api/v1/collection/import-historical`는 ERP 경로가 아니다 — BDI/GDACS 과거 이력을
`raw_events`에 적재한다.

## 실행 전 준비

업로드 API가 받는 `contract_id` / `supplier_id` / `material_id`는 CSV의 `CTR-001` 같은
문자열이 아니라 **DB가 생성한 숫자 PK**다. 먼저 조회해야 한다.

```bash
docker compose exec postgres psql -U postgres -d batteryrisk -c "SELECT c.contract_id, c.erp_contract_id, c.supplier_id, m.material_id FROM contracts c JOIN materials m ON m.erp_material_id = 'MAT-LI-CARB' WHERE c.erp_contract_id IN ('CTR-001','CTR-002');"
```

검증 규칙 (`DocumentService.validate`, `spring-backend/.../service/DocumentService.java:256`):
- 확장자는 `pdf`, `txt`, `csv`만 허용 → 그 외는 `UNSUPPORTED_DOCUMENT_TYPE`
- 확장자와 MIME이 불일치하면 `INVALID_MIME_TYPE` (예: `.pdf`를 `text/plain`으로 보내면 거절)
- `(contract_id, supplier_id)` 조합이 `contracts`에 없으면 `CONTRACT_SUPPLIER_NOT_FOUND`
- `material_id`가 `materials`에 없으면 `MATERIAL_NOT_FOUND`
- 최대 크기 `app.upload.max-file-size` 기본 50MB
- 동일 `(contract_id, content_hash)` 재업로드는 `duplicate=true`로 기존 문서를 반환

## 업로드

```bash
curl -X POST http://localhost:8080/api/v1/documents -F "file=@data/mock-upload/CTR-001_mock_supply_agreement.txt;type=text/plain" -F "contract_id=1" -F "supplier_id=1" -F "material_id=1" -F "document_type=CONTRACT"
```

```bash
curl -X POST http://localhost:8080/api/v1/documents -F "file=@data/mock-upload/CTR-002_mock_supply_agreement.pdf;type=application/pdf" -F "contract_id=2" -F "supplier_id=2" -F "material_id=1" -F "document_type=CONTRACT"
```

```bash
curl -X POST http://localhost:8080/api/v1/documents -F "file=@data/mock-upload/CTR-001_mock_delivery_schedule.csv;type=text/csv" -F "contract_id=1" -F "supplier_id=1" -F "material_id=1" -F "document_type=SPECIFICATION"
```

상태 확인:

```bash
curl http://localhost:8080/api/v1/documents/{document_id}
```

`status=COMPLETED`, `chunk_count > 0`이면 ChromaDB 적재까지 성공한 것이다.
`OPENAI_API_KEY`가 없으면 `mock_embedding=true`로 mock 임베딩이 쓰인다.

## 거절 케이스 확인

```bash
cp data/mock-upload/CTR-001_mock_supply_agreement.txt /tmp/mock.docx && curl -X POST http://localhost:8080/api/v1/documents -F "file=@/tmp/mock.docx;type=text/plain" -F "contract_id=1" -F "supplier_id=1" -F "material_id=1"
```

`UNSUPPORTED_DOCUMENT_TYPE`이 나와야 정상이다.
