#!/usr/bin/env python3
"""계약서 텍스트를 ChromaDB(RAG)에 적재하는 운영 스크립트.

핵심 원칙
  - ERP 문자열 ID(CTR-010 등)는 Spring PostgreSQL의 PK(정수)로 변환해 적재한다.
    RAG 검색 필터가 이 정수 PK를 사용하며, Chroma에는 문자열 ID를 저장하지 않기 때문이다.
  - 매핑의 유일한 출처는 PostgreSQL(contracts 테이블의 erp_contract_id ↔ contract_id)이다.
    이 스크립트는 docker exec psql로 매핑을 읽는다(호스트에 psycopg가 없어도 동작).
  - 적재는 이미 검증된 FastAPI 엔드포인트 /api/v1/documents/process 를 재사용한다.
    (청킹·임베딩·Chroma upsert 로직을 중복 구현하지 않는다.)
  - 실제 계약 원본이 있으면(--source-dir 안의 {ERP_ID}.pdf|.txt) 그걸 쓰고,
    없으면 ERP 메타데이터 + 계약검토요청(05_contract_review_requests.csv)으로 계약 텍스트를 생성한다.

실임베딩 vs Mock
  - 적재되는 임베딩의 실/목업 여부는 fastapi 컨테이너의 EMBEDDING_PROVIDER 설정이 결정한다.
    실임베딩으로 적재하려면 먼저 EMBEDDING_PROVIDER=openai + OPENAI_API_KEY로 컨테이너를 재기동한다.
  - 이 스크립트는 어느 모드든 그대로 동작하며, 응답의 mock_embedding 값을 그대로 보고한다.

사전 조건
  - docker compose가 떠 있어야 한다(postgres, chroma, fastapi).
  - FastAPI가 호스트에 노출돼 있어야 한다(docker-compose의 FASTAPI_PORT, 기본 8000).

사용 예 (호스트에서, 저장소 루트 기준)
  python fastapi-ai/scripts/load_contracts.py               # 전체 계약 적재
  python fastapi-ai/scripts/load_contracts.py --only CTR-010 # 특정 계약만
  python fastapi-ai/scripts/load_contracts.py --dry-run      # 적재 없이 매핑/텍스트 미리보기
"""

from __future__ import annotations

import argparse
import csv
import json
import mimetypes
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path

# 저장소 루트 = 이 파일에서 두 단계 위(fastapi-ai/scripts/ → 루트)
REPO_ROOT = Path(__file__).resolve().parents[2]
REVIEW_CSV = REPO_ROOT / "data" / "ERP_data" / "agent-csv" / "05_contract_review_requests.csv"

# psql이 컬럼을 이 순서로 내보낸다(탭 구분). 순서를 바꾸면 아래 파싱도 함께 고쳐야 한다.
_MAPPING_SQL = (
    "SELECT c.contract_id, c.erp_contract_id, c.supplier_id, c.material_id, "
    "c.contract_name, c.status, COALESCE(c.contract_role,''), "
    "COALESCE(to_char(c.start_date,'YYYY-MM-DD'),''), "
    "COALESCE(to_char(c.end_date,'YYYY-MM-DD'),''), c.currency_code, "
    "COALESCE(s.supplier_name,''), COALESCE(s.erp_supplier_id,''), COALESCE(s.country_code,''), "
    "COALESCE(m.material_name,''), COALESCE(m.erp_material_id,''), COALESCE(m.material_category,'') "
    "FROM contracts c "
    "LEFT JOIN suppliers s ON s.supplier_id = c.supplier_id "
    "LEFT JOIN materials m ON m.material_id = c.material_id "
    "WHERE c.erp_contract_id IS NOT NULL AND c.erp_contract_id <> '' "
    "AND c.supplier_id IS NOT NULL AND c.material_id IS NOT NULL "
    "ORDER BY c.contract_id"
)

# 계약검토요청의 questionCode → 생성 계약 텍스트에 넣을 조항 제목/본문
_CLAUSE_LIBRARY = {
    "DELIVERY_DELAY_NOTICE": (
        "납기 지연 통지",
        "공급자는 인도 예정일보다 납기가 지연될 것이 예상되는 경우 지체 없이 서면으로 구매자에게 "
        "지연 사유와 예상 지연 일수, 복구 계획을 통지하여야 한다. 통지 지연으로 인한 손해는 공급자가 부담한다.",
    ),
    "FORCE_MAJEURE": (
        "불가항력",
        "천재지변, 전쟁, 파업, 정부의 수출입 규제 등 당사자의 합리적 통제를 벗어난 사유로 인한 이행 지체 또는 "
        "불이행은 불가항력으로 본다. 다만 불가항력 사유가 30일을 초과하여 지속되는 경우 구매자는 계약을 해지할 수 있다.",
    ),
    "ALTERNATIVE_SUPPLIER_RESTRICTION": (
        "대체 공급사 제한",
        "본 계약 기간 중 구매자가 동일 품목을 제3의 공급사로부터 조달하고자 하는 경우, 사전에 공급자와 협의하여야 하며 "
        "승인된 대체 공급사 목록에 한하여 조달할 수 있다. 승인 대체 공급사가 없는 경우 공급 공백에 대한 책임은 별도로 정한다.",
    ),
    "DELIVERY_PENALTY": (
        "납기 지연 위약금",
        "공급자의 귀책으로 납기가 지연되는 경우, 지연 일수에 대해 지연 물량 금액의 0.1%를 1일당 위약금으로 부과한다. "
        "위약금 누계는 해당 발주 금액의 10%를 상한으로 한다.",
    ),
}


@dataclass(frozen=True)
class ContractRow:
    contract_pk: int
    erp_contract_id: str
    supplier_pk: int
    material_pk: int
    contract_name: str
    status: str
    contract_role: str
    start_date: str
    end_date: str
    currency: str
    supplier_name: str
    erp_supplier_id: str
    country_code: str
    material_name: str
    erp_material_id: str
    material_category: str


def fetch_mapping(pg_container: str, pg_user: str, pg_db: str) -> list[ContractRow]:
    """docker exec psql로 계약↔PK 매핑을 읽는다."""
    args = [
        "docker", "exec", pg_container,
        "psql", "-U", pg_user, "-d", pg_db,
        "-tAF", "\t", "-c", _MAPPING_SQL,
    ]
    try:
        completed = subprocess.run(
            args, capture_output=True, text=True, encoding="utf-8", check=True,
        )
    except FileNotFoundError:
        sys.exit("ERROR: docker CLI를 찾지 못했습니다. Docker가 설치/실행 중인지 확인하세요.")
    except subprocess.CalledProcessError as exc:
        sys.exit(
            f"ERROR: PostgreSQL 조회 실패 (container={pg_container}).\n{exc.stderr.strip()}"
        )

    rows: list[ContractRow] = []
    for line in completed.stdout.splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != 16:
            print(f"  경고: 예상치 못한 컬럼 수({len(fields)}), 건너뜀: {line[:60]}...")
            continue
        rows.append(ContractRow(
            contract_pk=int(fields[0]),
            erp_contract_id=fields[1],
            supplier_pk=int(fields[2]),
            material_pk=int(fields[3]),
            contract_name=fields[4],
            status=fields[5],
            contract_role=fields[6],
            start_date=fields[7],
            end_date=fields[8],
            currency=fields[9],
            supplier_name=fields[10],
            erp_supplier_id=fields[11],
            country_code=fields[12],
            material_name=fields[13],
            erp_material_id=fields[14],
            material_category=fields[15],
        ))
    return rows


def load_review_requests() -> dict[str, dict[str, str]]:
    """05_contract_review_requests.csv를 erp_contract_id 기준으로 읽는다(있으면)."""
    if not REVIEW_CSV.exists():
        return {}
    result: dict[str, dict[str, str]] = {}
    with REVIEW_CSV.open(encoding="utf-8-sig", newline="") as handle:
        for record in csv.DictReader(handle):
            contract_id = (record.get("contractId") or "").strip()
            if contract_id:
                result[contract_id] = record
    return result


def find_source_file(source_dir: Path, erp_contract_id: str) -> Path | None:
    for suffix in (".pdf", ".txt"):
        candidate = source_dir / f"{erp_contract_id}{suffix}"
        if candidate.exists():
            return candidate
    return None


def generate_contract_text(row: ContractRow, review: dict[str, str] | None) -> str:
    """실제 계약 원본이 없을 때, 메타데이터로 검색 가능한 계약 텍스트를 생성한다."""
    lines: list[str] = [
        f"{row.contract_name}",
        f"(계약번호 ERP-ID {row.erp_contract_id} / 상태 {row.status} / 역할 {row.contract_role or 'N/A'})",
        "",
        "제1조 (계약의 목적)",
        f"본 계약은 공급자 {row.supplier_name}({row.erp_supplier_id}, 국가 {row.country_code})가 "
        f"구매자에게 {row.material_name}({row.erp_material_id}, 분류 {row.material_category}) 품목을 "
        f"안정적으로 공급하는 것을 목적으로 한다.",
        "",
        "제2조 (계약 기간)",
        f"계약 기간은 {row.start_date or '미정'}부터 {row.end_date or '미정'}까지로 하며, "
        f"결제 통화는 {row.currency}로 한다.",
        "",
        "제3조 (물량 및 인도)",
        "공급자는 구매자의 발주 계획에 따라 합의된 물량을 인도 장소로 적기 납품하여야 하며, "
        "품질은 구매자가 정한 규격과 인증 요건을 충족하여야 한다.",
    ]

    # 계약검토요청에 담긴 조항 코드가 있으면 해당 조항을 우선 반영(검색 적중률 향상).
    clause_index = 4
    codes: list[str] = []
    if review:
        codes = [c.strip() for c in (review.get("questionCodes") or "").split(";") if c.strip()]
    for code in codes:
        title, body = _CLAUSE_LIBRARY.get(
            code, (code.replace("_", " ").title(), "본 조항에 대한 세부 조건은 부속 합의서에 따른다.")
        )
        lines += ["", f"제{clause_index}조 ({title})", body]
        clause_index += 1

    if review and (review.get("reviewReason") or "").strip():
        lines += [
            "",
            f"제{clause_index}조 (리스크 검토 메모)",
            f"본 계약은 다음 사유로 검토 대상이다: {review['reviewReason'].strip()} "
            f"(노출 등급 {review.get('exposureLevel', 'N/A')}).",
        ]

    lines += [
        "",
        "[NOTE] 본 문서는 실제 계약 원본 파일이 없어 ERP 메타데이터로 생성된 대체 텍스트입니다. "
        "실제 계약서 PDF를 --source-dir 경로에 두면 그 원본이 우선 적재됩니다.",
    ]
    return "\n".join(lines)


def post_multipart(url: str, fields: dict[str, str], file_field: str,
                   filename: str, file_bytes: bytes, timeout: float) -> dict:
    """stdlib만으로 multipart/form-data POST (호스트에 외부 패키지 불필요)."""
    boundary = f"----loadcontracts{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    body = bytearray()
    for name, value in fields.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        body += f"{value}\r\n".encode()
    body += f"--{boundary}\r\n".encode()
    body += (
        f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode()
    body += file_bytes + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    request = urllib.request.Request(
        url, data=bytes(body), method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(
            f"FastAPI 연결 실패: {exc.reason}. FASTAPI_PORT 노출과 컨테이너 상태를 확인하세요."
        ) from exc


def check_health(api_base: str, timeout: float) -> None:
    try:
        with urllib.request.urlopen(f"{api_base}/health", timeout=timeout) as response:
            response.read()
    except Exception as exc:  # noqa: BLE001 - 사전 점검용, 상세 사유 그대로 안내
        sys.exit(
            f"ERROR: FastAPI({api_base})에 연결할 수 없습니다: {exc}\n"
            "  - docker compose ps 로 battery-risk-fastapi 가 떠 있는지\n"
            "  - docker-compose.yml의 fastapi ports(FASTAPI_PORT) 노출 여부를 확인하세요."
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="계약서를 ChromaDB(RAG)에 적재한다.")
    parser.add_argument("--api-base", default="http://localhost:8000", help="FastAPI 베이스 URL")
    parser.add_argument("--pg-container", default="battery-risk-postgres", help="PostgreSQL 컨테이너 이름")
    parser.add_argument("--pg-user", default="battery_app")
    parser.add_argument("--pg-db", default="battery_risk")
    parser.add_argument("--source-dir", default=str(REPO_ROOT / "data" / "contracts"),
                        help="실제 계약 원본({ERP_ID}.pdf|.txt) 디렉토리. 없으면 텍스트를 생성한다.")
    parser.add_argument("--only", default="", help="쉼표로 구분한 ERP 계약 ID만 적재 (예: CTR-010,CTR-011)")
    parser.add_argument("--document-type", default="CONTRACT")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--dry-run", action="store_true", help="적재하지 않고 매핑/텍스트만 출력")
    args = parser.parse_args()

    source_dir = Path(args.source_dir)
    only = {token.strip() for token in args.only.split(",") if token.strip()}

    print(f"[1/3] PostgreSQL에서 계약↔PK 매핑 조회 (container={args.pg_container}) ...")
    rows = fetch_mapping(args.pg_container, args.pg_user, args.pg_db)
    if only:
        rows = [row for row in rows if row.erp_contract_id in only]
    if not rows:
        sys.exit("ERROR: 적재할 계약이 없습니다(erp_contract_id/supplier_id/material_id 조건 확인).")
    print(f"      대상 계약 {len(rows)}건")

    reviews = load_review_requests()

    if not args.dry_run:
        print(f"[2/3] FastAPI 상태 점검 ({args.api_base}) ...")
        check_health(args.api_base, args.timeout)

    print("[3/3] 적재 시작 ...")
    process_url = f"{args.api_base}/api/v1/documents/process"
    ok = 0
    failed: list[str] = []
    real_count = 0
    for row in rows:
        source_file = find_source_file(source_dir, row.erp_contract_id)
        if source_file:
            file_bytes = source_file.read_bytes()
            filename = source_file.name
            origin = f"FILE {source_file.name}"
        else:
            file_bytes = generate_contract_text(row, reviews.get(row.erp_contract_id)).encode("utf-8")
            filename = f"{row.erp_contract_id}.txt"
            origin = "GENERATED"

        pk_info = (f"{row.erp_contract_id}→c{row.contract_pk}/"
                   f"s{row.supplier_pk}/m{row.material_pk}")
        if args.dry_run:
            print(f"  [DRY] {pk_info}  [{origin}]  {len(file_bytes)}B  {row.contract_name}")
            continue

        fields = {
            "document_id": f"CONTRACT-{row.erp_contract_id}",
            "contract_id": str(row.contract_pk),
            "supplier_id": str(row.supplier_pk),
            "material_id": str(row.material_pk),
            "document_type": args.document_type,
            "force_reprocess": "true",
        }
        try:
            payload = post_multipart(
                process_url, fields, "file", filename, file_bytes, args.timeout,
            )
            data = payload.get("data", {})
            mock = data.get("mock_embedding", True)
            real_count += 0 if mock else 1
            print(
                f"  OK  {pk_info}  [{origin}]  chunks={data.get('chunk_count')}  "
                f"embed={data.get('embedding_type')}  mock={mock}"
            )
            ok += 1
        except RuntimeError as exc:
            print(f"  FAIL {pk_info}  [{origin}]  {exc}")
            failed.append(row.erp_contract_id)

    if args.dry_run:
        print("\n(dry-run) 실제 적재는 하지 않았습니다.")
        return 0

    print(f"\n완료: 성공 {ok}건 / 실패 {len(failed)}건 (실임베딩 {real_count}건)")
    if real_count == 0 and ok > 0:
        print("  [주의] 전부 mock 임베딩입니다. 실임베딩으로 적재하려면 fastapi 컨테이너를 "
              "EMBEDDING_PROVIDER=openai + OPENAI_API_KEY 로 재기동하세요.")
    if failed:
        print(f"  실패 계약: {', '.join(failed)}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
