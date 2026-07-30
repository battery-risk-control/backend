#!/usr/bin/env python3
"""
ERP Context 회귀 검증 스크립트 (S10 완료 기준: "Agent Expected 결과와 비교")

동작:
  1) agent-csv/01_erp_agent_requests.csv 의 각 요청(materialId, supplierId, asOf)을 읽어
  2) 실행 중인 Spring Boot의 POST /api/v1/erp/context 를 호출하고
  3) 응답을 agent-csv/04_erp_exposure_expected.csv 의 기대값(ERP Context 6개 필드)과 비교한다.

검증 대상 필드 (POST /erp/context 가 실제로 반환하는 값):
  inventory_days, safety_stock_days, next_eta_days,
  expected_supply_gap_days, supplier_dependency_ratio, data_quality_status

  ※ 04 파일의 exposure 점수 계열(erpExposureScore/exposureLevel/forcedCritical 등)은
     별도 ERP-Exposure 점수 엔진 소관이라 /erp/context 응답에 없으므로 이 스크립트 범위 밖이다.

주의 - 타임존:
  agent-csv 의 asOf 는 "...T00:00:00+09:00"(자정)인데, 현재 백엔드는 Jackson 기본 설정으로
  OffsetDateTime 을 UTC 로 정규화하면서 날짜가 하루 앞당겨지는 알려진 이슈가 있다.
  이 스크립트는 "같은 업무일(KST 기준)"을 유지하기 위해 asOf 를 해당 날짜 정오(T12:00:00+09:00)로
  보내 UTC 변환 후에도 날짜가 보존되게 한다. (버그 자체는 별도 이슈로 관리)

사용법:
  # Spring Boot(8080)와 Postgres(시드 적재됨)가 떠 있는 상태에서
  python scripts/verify_erp_context.py
"""

import csv
import json
import os
import sys
import urllib.error
import urllib.request

# Windows 콘솔(cp949)에서도 한글/유니코드 출력이 깨지지 않도록 UTF-8 로 강제한다.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

BASE_URL = os.environ.get("SPRING_BASE_URL", "http://localhost:8080")
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT_CSV_DIR = os.path.join(REPO_ROOT, "data", "ERP_data", "agent-csv")
REQUESTS_CSV = os.path.join(AGENT_CSV_DIR, "01_erp_agent_requests.csv")
EXPECTED_CSV = os.path.join(AGENT_CSV_DIR, "04_erp_exposure_expected.csv")

# 검증에 사용하는 전용 계정 (역할 무관 - 아무 역할이나 가능)
VERIFY_USER = os.environ.get("VERIFY_USER", "erp_verify_bot")
VERIFY_PASS = os.environ.get("VERIFY_PASS", "VerifyBot123!")

# (기대 CSV 컬럼, 응답 JSON 필드, 숫자 여부)
FIELDS = [
    ("inventoryDays", "inventory_days", True),
    ("safetyStockDays", "safety_stock_days", True),
    ("nextEtaDays", "next_eta_days", True),
    ("expectedSupplyGapDays", "expected_supply_gap_days", True),
    ("supplierDependencyRatio", "supplier_dependency_ratio", True),
    ("dataQualityStatus", "data_quality_status", False),
]
TOLERANCE = 1e-4


def http_post(path, payload, token=None):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
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
    http_post("/api/v1/auth/signup", {
        "username": VERIFY_USER, "password": VERIFY_PASS,
        "name": "ERP Verify Bot", "role": "PURCHASING",
    })
    status, body = http_post("/api/v1/auth/login", {
        "username": VERIFY_USER, "password": VERIFY_PASS,
    })
    if status != 200 or not body.get("success"):
        print(f"[로그인 실패] status={status} body={body}")
        sys.exit(2)
    return body["data"]["access_token"]


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh))


def parse_expected(raw):
    """빈 문자열은 None(=UNKNOWN 시 계산 안 함)으로 처리."""
    return None if raw is None or raw.strip() == "" else raw.strip()


def compare(expected_raw, actual, is_numeric):
    expected = parse_expected(expected_raw)
    if expected is None:
        return actual is None, "None", _fmt(actual)
    if actual is None:
        return False, expected, "None"
    if is_numeric:
        ok = abs(float(expected) - float(actual)) <= TOLERANCE
    else:
        ok = str(expected) == str(actual)
    return ok, expected, _fmt(actual)


def _fmt(value):
    if value is None:
        return "None"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def as_of_noon(raw_as_of):
    """자정 asOf 를 같은 날짜 정오(+09:00)로 바꿔 타임존 정규화 이슈를 피한다."""
    date_part = raw_as_of.split("T", 1)[0]
    return f"{date_part}T12:00:00+09:00"


def main():
    requests = read_csv(REQUESTS_CSV)
    expected = {row["requestId"]: row for row in read_csv(EXPECTED_CSV)}
    token = ensure_token()

    total = len(requests)
    passed = 0
    print(f"ERP Context 회귀 검증 — 요청 {total}건\n" + "=" * 78)

    for req in requests:
        rid = req["requestId"]
        material = req["affectedMaterialId"]
        supplier = req.get("affectedSupplierId") or None
        exp = expected.get(rid)
        if exp is None:
            print(f"[{rid}] 기대값 없음 (04 파일에 없음) — 건너뜀")
            continue

        payload = {"erp_material_id": material, "as_of": as_of_noon(req["asOf"])}
        if supplier:
            payload["erp_supplier_id"] = supplier
        status, body = http_post("/api/v1/erp/context", payload, token)

        if status != 200 or not body.get("success"):
            code = (body.get("error") or {}).get("code", status)
            print(f"[{rid}] {material:<12} 호출 실패: {code}")
            continue

        data = body["data"]
        row_ok = True
        details = []
        for exp_col, json_field, is_num in FIELDS:
            ok, exp_v, act_v = compare(exp.get(exp_col), data.get(json_field), is_num)
            row_ok = row_ok and ok
            if not ok:
                details.append(f"    ✗ {json_field}: 기대={exp_v} 실제={act_v}")

        if row_ok:
            passed += 1
            print(f"[{rid}] {material:<12} ✅ PASS "
                  f"(inv_days={_fmt(data.get('inventory_days'))}, "
                  f"dq={data.get('data_quality_status')})")
        else:
            print(f"[{rid}] {material:<12} ❌ FAIL")
            print("\n".join(details))

    print("=" * 78)
    print(f"결과: {passed}/{total} PASS")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
