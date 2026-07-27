# 계약서 적재 스크립트 (`load_contracts.py`)

계약서 텍스트를 ChromaDB(RAG)에 **올바른 PK로 일괄 적재**하는 운영 스크립트입니다.
앱이 의존하는 코드가 아니라, 필요할 때 한 번씩 실행하는 도구입니다.

---

## 무엇을 하나

1. **매핑 조회** — PostgreSQL `contracts` 테이블에서 `erp_contract_id`(문자열) ↔ `contract_id`(숫자 PK)와
   supplier/material PK를 읽어옵니다. `docker exec psql`로 조회하므로 호스트에 psycopg가 없어도 됩니다.
2. **원본 텍스트 확보** — `data/contracts/{ERP_ID}.pdf|.txt`에 실제 계약서가 있으면 그것을 사용하고,
   없으면 ERP 메타데이터 + 계약검토요청(조항 코드)으로 계약 텍스트를 **생성**합니다.
3. **적재** — 검증된 FastAPI 엔드포인트 `POST /api/v1/documents/process`를 호출해
   청킹 → 임베딩 → Chroma upsert를 태웁니다. (임베딩 로직을 중복 구현하지 않습니다.)
4. **보고** — 계약별 청크 수·임베딩 타입·mock 여부를 출력합니다.

---

## ⚠️ 매핑 규칙 (가장 중요)

RAG 검색 필터에 쓰는 숫자형 `contract_id`/`supplier_id`/`material_id`는 **Spring PostgreSQL의 PK(bigint)** 입니다.
ERP 문자열 ID(`CTR-010` 등)가 **아닙니다.**

- 문자열 ID(`CTR-010`, `SUP-COD-01`, `MAT-CO-SULF`) → DB의 `erp_contract_id`/`erp_supplier_id`/`erp_material_id` 컬럼
- 숫자 PK(`11`, `6`, `5`) → 적재 시 시퀀스로 자동 부여된 값이며, **이 값을 Chroma 메타데이터에 저장**

예: `CTR-010 → contract_id 11`, `SUP-COD-01 → supplier_id 6`, `MAT-CO-SULF → material_id 5`

> Chroma에는 문자열 ID를 저장하지 않으므로, 문자열로 필터하면 항상 0건입니다.
> 이 스크립트는 위 변환을 자동으로 처리합니다.

---

## 사전 조건

1. `docker compose`가 실행 중이어야 합니다 (`postgres`, `chroma`, `fastapi`).
2. **FastAPI가 호스트에 노출**돼 있어야 합니다. `docker-compose.yml`의 fastapi 서비스에 다음이 있어야 합니다:
   ```yaml
   fastapi:
     ports:
       - "${FASTAPI_PORT:-8000}:8000"
   ```
   > 스크립트 파일만 받은 경우, 이 `ports` 블록과 `.env`의 `FASTAPI_PORT`가 함께 있어야 동작합니다.
   > 없으면 실행 시 "FastAPI 연결 실패"가 납니다.
3. (실임베딩으로 적재할 때만) `.env`에 아래 설정 후 fastapi 재기동:
   ```bash
   EMBEDDING_PROVIDER=openai
   OPENAI_API_KEY=sk-...
   ```
   ```bash
   docker compose up -d --force-recreate fastapi
   ```
   - 컬렉션이 `contract_documents_mock_v1`(목업) ↔ `contract_documents_openai_v1`(실임베딩)로 분리됩니다.
   - 임베딩 실/목업 여부는 `EMBEDDING_PROVIDER`가 결정하며 `MOCK_MODE`와는 독립입니다.

---

## 사용법

저장소 루트에서 실행합니다.

```bash
# 전체 계약 적재
python fastapi-ai/scripts/load_contracts.py

# 특정 계약만
python fastapi-ai/scripts/load_contracts.py --only CTR-010,CTR-011

# 적재 없이 매핑/텍스트만 미리보기 (PostgreSQL만 필요, FastAPI 불필요)
python fastapi-ai/scripts/load_contracts.py --dry-run
```

### 주요 옵션

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `--api-base` | `http://localhost:8000` | FastAPI 베이스 URL |
| `--pg-container` | `battery-risk-postgres` | PostgreSQL 컨테이너 이름 |
| `--pg-user` / `--pg-db` | `battery_app` / `battery_risk` | DB 접속 정보 |
| `--source-dir` | `data/contracts` | 실제 계약 원본(`{ERP_ID}.pdf\|.txt`) 디렉토리 |
| `--only` | (전체) | 쉼표로 구분한 ERP 계약 ID만 적재 |
| `--document-type` | `CONTRACT` | 문서 유형 라벨 |
| `--dry-run` | off | 적재하지 않고 미리보기 |
| `--timeout` | `60` | 요청 타임아웃(초) |

### 실행 예시 출력

```
[1/3] PostgreSQL에서 계약↔PK 매핑 조회 (container=battery-risk-postgres) ...
      대상 계약 1건
[2/3] FastAPI 상태 점검 (http://localhost:8000) ...
[3/3] 적재 시작 ...
  OK  CTR-010→c11/s6/m5  [GENERATED]  chunks=9  embed=MOCK_TOKEN_HASH  mock=True

완료: 성공 1건 / 실패 0건 (실임베딩 0건)
```

- `[GENERATED]` = 실제 PDF가 없어 메타데이터로 생성한 텍스트, `[FILE ...]` = 실제 원본 파일 사용
- `mock=True` = 목업 임베딩. 실임베딩으로 적재하려면 위 사전 조건 3을 적용

---

## 현재 한계

- **실제 계약서 원본이 저장소에 없습니다.** `data/contracts/`에 실제 PDF를 넣지 않으면
  메타데이터로 만든 임시 텍스트가 적재됩니다. **의미 있는 검색을 위해 진짜 계약서를 넣으세요.**
  - 파일명 규칙: `data/contracts/{erp_contract_id}.pdf` (예: `CTR-010.pdf`) 또는 `.txt`
- 재실행은 안전합니다. `document_id`가 `CONTRACT-{ERP_ID}`로 고정되고 `force_reprocess=true`라
  같은 계약을 다시 돌리면 기존 청크를 지우고 새로 적재합니다(중복 누적 없음).

---

## 문제 해결

| 증상 | 원인 / 해결 |
|---|---|
| `FastAPI 연결 실패` | fastapi 컨테이너 미기동 또는 `ports` 미노출 → 사전 조건 1·2 확인 |
| `PostgreSQL 조회 실패` | 컨테이너명이 다름 → `--pg-container` 로 지정 |
| `docker CLI를 찾지 못했습니다` | Docker 미설치/미실행, 또는 원격 DB 환경 → 호스트에서 실행하거나 접속 방식 조정 |
| 검색 결과 0건 | 문자열 ID로 필터함 → 숫자 PK로 검색 (위 매핑 규칙 참고) |
| 전부 `mock=True` | fastapi가 mock 모드 → 사전 조건 3으로 실임베딩 전환 후 재적재 |

---

## 적재 결과 확인

```bash
# 컬렉션별 청크 수
curl -s http://localhost:8000/health

# 특정 계약 검색 (예: CTR-010 = contract_id 11)
curl -s -X POST http://localhost:8000/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query":"불가항력 납기 지연","filters":{"contract_id":11,"supplier_id":6},"top_k":3}'
```
```

<system-reminder>
The user hasn't sent a new message. Only take a new action if you have remaining TODOs that you have not yet completed. Otherwise, if you are finished with all your work, do not call any more tools and instead just end your turn by REPORTING to the user. Do not yield the turn with a tool call.