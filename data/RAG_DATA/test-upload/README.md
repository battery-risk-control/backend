# 데이터 관리 화면 RAG 모드 테스트용 계약서

구매팀 "데이터 관리" 화면(`/purchasing/data-management`)의 **RAG 데이터** 탭 —
**새 계약서를 등록하고 처음 임베딩하는** 경로를 손으로 확인할 때 쓴다.
ERP 쪽 짝은 [`data/ERP_data/test-csv/`](../../ERP_data/test-csv/README.md) 에 있다.

## 이 화면은 "신규 등록" 전용이다

| | 데이터 관리 (여기) | 계약/RAG 화면 |
|---|---|---|
| 하는 일 | 계약이 **없는** 조합에 계약서를 처음 등록 | **기존** 계약에 문서 추가 |
| 고르는 것 | 공급사 + 자재 | 기존 계약 |
| 결과 | 신규 계약 `CTR-XXX` 발급 + 신규 임베딩 | 기존 계약에 문서만 추가 |

고른 조합에 **이미 계약이 있으면 승인이 막히고** "계약/RAG 화면을 이용하세요" 안내가 뜬다.
그래서 아래 파일들은 전부 **시드에 계약이 없는 조합**으로 맞춰 두었다 — 안 그러면 전부 막힌다.

> 계약 목록이 아니라 공급사·자재를 고르는 이유가 여기 있다. 계약 목록으로 고르게 하면
> "이미 계약이 있는 조합"밖에 못 고르니 신규 등록 자체가 불가능하다.

## `data/mock-upload/` 와 무엇이 다른가

| | `data/mock-upload/` | 여기 (`test-upload/`) |
|---|---|---|
| 대상 API | `POST /api/v1/documents` | `/documents/contracts/preview` → `/confirm` |
| 쓰는 방법 | curl (숫자 PK를 직접 조회해 넘김) | 데이터 관리 **화면** |
| 확인하는 것 | 청킹·임베딩 파이프라인 | **필드 자동추출 + 신규 계약 발급** |

기존 mock은 한글 라벨이라 자동추출 정규식에 **하나도 걸리지 않는다.** 그래서 "자동추출이 되는"
경우를 볼 수 없어 이 세트를 따로 만들었다.

## 자동추출 규칙

`ContractFieldExtractor` 가 정규식 4개로만 뽑는다. LLM을 쓰지 않는다.

| 필드 | 인식하는 형태 | 비고 |
|---|---|---|
| 계약번호 | `Contract ID: <아무거나> (BA-2025-0001)` | **괄호 안**의 값을 가져간다 |
| 계약명 | 줄 전체가 `... SUPPLY AGREEMENT` 로 끝남 | **대문자만** 인식 (대소문자 구분함) |
| 발효일 | `Effective Date: 2026-01-01` | `YYYY-MM-DD` 만 |
| 만료일 | `Expiration Date: 2027-12-31` | `YYYY-MM-DD` 만 |

못 뽑은 필드는 `null`로 두고 **화면에서 사용자가 직접 채운다.** 버그가 아니라 설계다 —
범용 계약서 파서를 목표로 하지 않는다.

## 파일별 기대 결과

아래 추출 결과는 실제 `ContractFieldExtractor.extract()` 에 돌려서 얻은 값이다.

### `CTR-TEST-R1_supply_agreement_full.txt` — 4개 전부 추출

**대상 조합: `SUP-MYS-01` (Penang Advanced Materials) + `MAT-GR-SYN` (Synthetic Graphite)**

```
계약번호  BA-TEST-9001
계약명    SYNTHETIC GRAPHITE SUPPLY AGREEMENT
발효일    2026-01-01
만료일    2027-12-31
```

가장 기본 경로다. 미리보기 4칸이 전부 채워지고 `신규 발급 예정 CTR-XXX` 가 뜨며 승인 버튼이 열린다.
`SUP-MYS-01` 은 시드 계약이 **하나도 없는 유일한 공급사**라, 어떤 자재와 조합해도 신규 등록이 된다.

### `CTR-TEST-R2_supply_agreement_partial.txt` — 절반만 추출

**대상 조합: `SUP-ZAF-01` (Cape Manganese Resources) + `MAT-NI-SULF` (Nickel Sulfate)**

```
계약번호  null      ← "Agreement No.:" 라서 패턴에 안 걸림
계약명    NICKEL SULFATE SUPPLY AGREEMENT
발효일    2026-03-01
만료일    null      ← "31/12/2027" 은 ISO 형식이 아니라 파싱 실패
```

**빈 칸을 사용자가 채워 반영하는 경로**를 보려면 이걸 쓴다.

### `CTR-TEST-R3_supply_agreement_korean.txt` — 하나도 추출 안 됨

**대상 조합: `SUP-BRA-01` (Minas Battery Resources) + `MAT-GR-NAT` (Natural Graphite)**

```
계약번호  null
계약명    null
발효일    null
만료일    null
```

**완전 수동 입력 경로.** 실제 국문 계약서를 올렸을 때의 모습이다.

### `CTR-TEST-R4_supply_agreement_full.pdf` — PDF 2페이지, 4개 전부 추출

**대상 조합: `SUP-USA-01` (Nevada Critical Materials) + `MAT-LI-CARB` (Lithium Carbonate)**

```
계약번호  BA-TEST-9004
계약명    LITHIUM CARBONATE SUPPLY AGREEMENT
발효일    2026-02-15
만료일    2028-02-14
```

`data/mock-upload/make_mock_pdf.py` 의 생성기를 재사용한 무의존성 PDF다
(Helvetica/WinAnsi, ASCII 본문). 여러 페이지 추출도 같이 확인된다.

### 차단 경로도 보려면

아무 파일이나 올리고 대상을 **`SUP-CHL-01` + `MAT-LI-CARB`** 로 고르면 된다.
시드에 `CTR-001` 이 있는 조합이라 승인이 막히고 계약/RAG 안내가 뜬다.

시드에 계약이 있는 조합 30개는 `data/ERP_data/spring-csv/04_contracts.csv` 에서 확인할 수 있다.

## ⚠️ FastAPI가 떠 있어야 한다

**txt든 pdf든 텍스트 추출은 FastAPI(`/api/v1/documents/extract-text`)를 거친다.**
FastAPI가 죽어 있으면 Spring이 경고만 남기고 빈 문자열로 진행하므로, **R1을 올려도 4칸이
전부 비어서 온다.** 파일이 잘못된 게 아니다.

```bash
docker compose ps fastapi
```

자동추출이 안 될 때 여기부터 확인하면 된다.

## 화면에서 쓰는 법

1. 데이터 관리 → **RAG 데이터** 탭
2. **공급사**와 **자재**를 고른다 (계약 드롭다운이 아니다)
3. 파일 1건을 올린다 (RAG 모드는 **한 번에 한 건**만 받는다)
4. `내용 분석` → 추출된 필드가 수정 가능한 입력으로 뜬다
   - `신규 발급 예정 CTR-XXX` 면 진행 가능
   - `이미 존재` 면 승인이 막힌다 → 계약/RAG 화면으로
5. 확인·수정 후 `DB 반영 승인` → 계약 생성 + 문서 저장 + ChromaDB 임베딩

## 확장자·MIME 규칙

`DocumentService.validate` 기준이다. 확장자와 MIME이 어긋나면 `INVALID_MIME_TYPE` 으로 거절한다.

| 확장자 | 허용 MIME |
|---|---|
| `pdf` | `application/pdf` |
| `txt` | `text/plain` |
| `csv` | `text/csv`, `text/plain`, `application/csv` |

curl로 쏠 때는 `;type=` 을 반드시 맞춰야 한다.

```bash
curl -X POST http://localhost:8080/api/v1/documents/contracts/preview -H "Authorization: Bearer $TOKEN" -F "file=@data/RAG_DATA/test-upload/CTR-TEST-R1_supply_agreement_full.txt;type=text/plain" -F "erp_supplier_id=SUP-MYS-01" -F "erp_material_id=MAT-GR-SYN"
```
