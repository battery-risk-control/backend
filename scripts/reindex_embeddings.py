#!/usr/bin/env python3
"""
Embedding 재적재 헬퍼 (S16 - Mock -> 실제 OpenAI Embedding 전환용)

왜 필요한가:
  EMBEDDING_PROVIDER 를 openai 로 바꾸면 FastAPI 는 컬렉션을 분리한다.
    mock   -> contract_documents_mock_v1   (기존 청크가 여기 있음)
    openai -> contract_documents_openai_v1 (새로 생성, 비어 있음)
  즉 provider 만 바꾸면 기존 문서가 openai_v1 에 없어 RAG 검색이 0건이 된다.
  이 스크립트는 저장된 원본 문서를 다시 파이프라인에 태워 openai_v1 컬렉션을
  채운다. (mock_v1 은 그대로 두므로 EMBEDDING_PROVIDER=mock 으로 언제든 롤백 가능)

무엇을 하는가:
  1) 봇 계정으로 로그인해 JWT 를 얻는다.
  2) 재적재할 document_id 목록을 구한다.
       - 인자로 직접 주면 그걸 사용
       - 없으면 PostgreSQL(contract_documents)에서 조회
  3) 각 문서에 대해 POST /api/v1/documents/{id}/reprocess 를 호출한다.
       (서버가 원본 파일을 다시 읽어 현재 provider 로 재임베딩한다)
  4) 문서별 결과(chunk_count / embedding_type / embedding_version / mock)를
     표로 출력하고, 하나라도 mock=true 면 provider 가 아직 mock 이라고 경고한다.

선행 조건:
  - .env 에 OPENAI_API_KEY 와 EMBEDDING_PROVIDER=openai 설정
  - fastapi 컨테이너 재기동 (docker compose up -d fastapi)
  - Spring(8080)·PostgreSQL·FastAPI·Chroma 가 떠 있는 상태

사용법:
  # 1) 무엇을 재적재할지 미리 보기 (실제 호출 안 함)
  python scripts/reindex_embeddings.py --dry-run

  # 2) COMPLETED 문서 전부 재적재
  python scripts/reindex_embeddings.py

  # 3) 특정 문서만 재적재
  python scripts/reindex_embeddings.py con_abc123 po_def456

환경 변수(기본값):
  SPRING_BASE_URL   http://localhost:8080   Spring 주소
  VERIFY_USER       erp_verify_bot          로그인 봇 계정(verify 스크립트와 공유)
  VERIFY_PASS       VerifyBot123!
  DOCKER_COMPOSE    docker compose          compose 실행 명령(예: "docker-compose")
  PG_SERVICE        postgres                compose 안의 DB 서비스명
  PG_USER           battery_app             DB 사용자
  PG_DB             battery_risk            DB 이름
  PG_PASSWORD       battery_app_password    DB 비밀번호(psql 로컬 접속용)
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

# Windows 콘솔(cp949)에서도 한글/유니코드 출력이 깨지지 않도록 UTF-8 로 강제한다.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BASE_URL = os.environ.get("SPRING_BASE_URL", "http://localhost:8080")
VERIFY_USER = os.environ.get("VERIFY_USER", "erp_verify_bot")
VERIFY_PASS = os.environ.get("VERIFY_PASS", "VerifyBot123!")

DOCKER_COMPOSE = os.environ.get("DOCKER_COMPOSE", "docker compose")
PG_SERVICE = os.environ.get("PG_SERVICE", "postgres")
PG_USER = os.environ.get("PG_USER", "battery_app")
PG_DB = os.environ.get("PG_DB", "battery_risk")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "battery_app_password")


def http_json(method, path, payload=None, token=None, timeout=30):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(body)
        except json.JSONDecodeError:
            return exc.code, {"raw": body}
    except urllib.error.URLError as exc:
        print(f"[연결 실패] {BASE_URL} 에 접속할 수 없습니다: {exc}")
        print("  → Spring Boot(8080)가 실행 중인지 확인하세요.")
        sys.exit(2)


def ensure_token():
    # 계정이 없으면 만들고(있으면 무시), 로그인해서 토큰을 얻는다.
    http_json("POST", "/api/v1/auth/signup", {
        "username": VERIFY_USER, "password": VERIFY_PASS,
        "name": "ERP Verify Bot", "role": "PURCHASING",
    })
    status, body = http_json("POST", "/api/v1/auth/login", {
        "username": VERIFY_USER, "password": VERIFY_PASS,
    })
    if status != 200 or not body.get("success"):
        print(f"[로그인 실패] status={status} body={body}")
        sys.exit(2)
    return body["data"]["access_token"]


def fetch_document_ids(include_all):
    """PostgreSQL 에서 재적재 대상 document_id 를 조회한다.

    include_all=False 면 이전에 정상 적재된(COMPLETED) 문서만 대상으로 한다.
    """
    where = "" if include_all else "WHERE processing_status = 'COMPLETED'"
    query = (
        f"SELECT document_id FROM contract_documents {where} "
        "ORDER BY created_at;"
    )
    compose_parts = DOCKER_COMPOSE.split()
    cmd = compose_parts + [
        "exec", "-T",
        "-e", f"PGPASSWORD={PG_PASSWORD}",
        PG_SERVICE,
        "psql", "-U", PG_USER, "-d", PG_DB,
        "-t", "-A", "-c", query,
    ]
    try:
        result = subprocess.run(
            cmd, cwd=REPO_ROOT, capture_output=True, text=True, timeout=30,
        )
    except FileNotFoundError:
        print(f"[DB 조회 실패] '{compose_parts[0]}' 명령을 찾을 수 없습니다.")
        print("  → DOCKER_COMPOSE 환경변수로 실행 명령을 지정하거나, document_id 를 인자로 직접 넘기세요.")
        sys.exit(2)
    except subprocess.TimeoutExpired:
        print("[DB 조회 실패] psql 응답이 30초 내에 오지 않았습니다.")
        sys.exit(2)
    if result.returncode != 0:
        print("[DB 조회 실패] contract_documents 조회 중 오류가 발생했습니다.")
        print(result.stderr.strip() or result.stdout.strip())
        print("  → PG_SERVICE/PG_USER/PG_DB/PG_PASSWORD 환경변수를 확인하거나, document_id 를 인자로 직접 넘기세요.")
        sys.exit(2)
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def reprocess(document_id, token, timeout):
    status, body = http_json(
        "POST", f"/api/v1/documents/{document_id}/reprocess",
        token=token, timeout=timeout,
    )
    if status == 200 and body.get("success") and body.get("data"):
        d = body["data"]
        return {
            "document_id": d.get("document_id", document_id),
            "ok": True,
            "processing_status": d.get("processing_status", "?"),
            "chunk_count": d.get("chunk_count", 0),
            "embedding_type": d.get("embedding_type", "?"),
            "embedding_version": d.get("embedding_version", "?"),
            "mock": bool(d.get("mock", False)),
            "detail": "",
        }
    # 실패: 에러 코드/메시지 추출
    err = body.get("error") if isinstance(body, dict) else None
    code = (err or {}).get("code", "") if isinstance(err, dict) else ""
    message = (err or {}).get("message", "") if isinstance(err, dict) else ""
    detail = f"{code} {message}".strip() or f"HTTP {status} {body}"
    return {
        "document_id": document_id,
        "ok": False,
        "processing_status": "FAILED",
        "chunk_count": 0,
        "embedding_type": "-",
        "embedding_version": "-",
        "mock": False,
        "detail": detail,
    }


def print_table(rows):
    headers = ["document_id", "status", "chunks", "embedding_type", "embedding_version", "mock"]
    table = [headers]
    for r in rows:
        table.append([
            r["document_id"],
            "OK" if r["ok"] else "FAIL",
            str(r["chunk_count"]),
            r["embedding_type"],
            r["embedding_version"],
            "true" if r["mock"] else "false",
        ])
    widths = [max(len(row[i]) for row in table) for i in range(len(headers))]
    for idx, row in enumerate(table):
        line = "  ".join(cell.ljust(widths[i]) for i, cell in enumerate(row))
        print(line)
        if idx == 0:
            print("  ".join("-" * widths[i] for i in range(len(headers))))
    # 실패 상세는 표 아래에 따로
    for r in rows:
        if not r["ok"]:
            print(f"    └ {r['document_id']}: {r['detail']}")


def main():
    parser = argparse.ArgumentParser(
        description="저장된 원본 문서를 현재 Embedding provider 로 재적재한다 (S16).",
    )
    parser.add_argument(
        "document_ids", nargs="*",
        help="재적재할 document_id (생략하면 PostgreSQL 에서 조회)",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="대상만 출력하고 실제 재적재는 하지 않는다.",
    )
    parser.add_argument(
        "--all", action="store_true",
        help="COMPLETED 뿐 아니라 모든 상태의 문서를 대상으로 한다.",
    )
    parser.add_argument(
        "--sleep", type=float, default=0.0,
        help="문서 사이 대기 시간(초). OpenAI rate limit 이 걱정되면 사용.",
    )
    parser.add_argument(
        "--timeout", type=float, default=120.0,
        help="문서 1건 재처리 HTTP 타임아웃(초). 실제 OpenAI 호출 시간을 고려해 넉넉히.",
    )
    args = parser.parse_args()

    # 1) 대상 문서 결정
    if args.document_ids:
        document_ids = args.document_ids
        source = "명령행 인자"
    else:
        document_ids = fetch_document_ids(include_all=args.all)
        scope = "전체" if args.all else "COMPLETED"
        source = f"PostgreSQL contract_documents ({scope})"

    print(f"대상 문서: {len(document_ids)}건  (출처: {source})")
    if not document_ids:
        print("재적재할 문서가 없습니다.")
        return 0
    for document_id in document_ids:
        print(f"  - {document_id}")

    if args.dry_run:
        print("\n[dry-run] 실제 재적재는 하지 않았습니다.")
        print("실제 실행: python scripts/reindex_embeddings.py")
        return 0

    # 2) 로그인
    print("\n로그인 중...")
    token = ensure_token()

    # 3) 문서별 재적재
    print(f"재적재 시작 (문서 1건당 최대 {args.timeout:.0f}초 대기)\n")
    rows = []
    for index, document_id in enumerate(document_ids, start=1):
        print(f"[{index}/{len(document_ids)}] {document_id} ... ", end="", flush=True)
        row = reprocess(document_id, token, args.timeout)
        rows.append(row)
        if row["ok"]:
            print(f"OK ({row['chunk_count']} chunks, {row['embedding_type']})")
        else:
            print(f"FAIL ({row['detail']})")
        if args.sleep > 0 and index < len(document_ids):
            time.sleep(args.sleep)

    # 4) 요약
    print("\n=== 결과 ===")
    print_table(rows)

    ok_count = sum(1 for r in rows if r["ok"])
    fail_count = len(rows) - ok_count
    mock_count = sum(1 for r in rows if r["ok"] and r["mock"])
    real_count = ok_count - mock_count
    total_chunks = sum(r["chunk_count"] for r in rows if r["ok"])
    print(
        f"\n성공 {ok_count} / 실패 {fail_count}  "
        f"(실제 임베딩 {real_count}, mock {mock_count}, 총 {total_chunks} chunks)"
    )

    # 5) provider 상태 경고 — 재적재 자체는 성공했는데 여전히 mock 이면 전환이 안 된 것
    if mock_count > 0:
        print("\n[경고] mock=true 로 적재된 문서가 있습니다.")
        print("  현재 FastAPI 가 아직 Mock Embedding 을 쓰고 있다는 뜻입니다.")
        print("  확인:")
        print("   1) .env 에 EMBEDDING_PROVIDER=openai 와 OPENAI_API_KEY 설정")
        print("   2) docker compose up -d fastapi  (컨테이너 재기동으로 .env 반영)")
        print("   3) 이 스크립트 다시 실행")

    if real_count > 0 and fail_count == 0 and mock_count == 0:
        print("\n[완료] 모든 문서가 실제 OpenAI Embedding 으로 openai_v1 컬렉션에 적재됐습니다.")
        print("  검증: POST /api/v1/rag/search 응답의 mock_embedding 이 false 인지 확인하세요.")

    # 실패나 예상치 못한 mock 이 있으면 비정상 종료 코드
    return 0 if fail_count == 0 and mock_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
