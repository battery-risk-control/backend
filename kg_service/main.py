"""
KG 리졸버 서비스 (CLAUDE.md 결정사항 10번, 1단계).

Triage를 통과한 뉴스가 LLM 추출(country + affected_materials)을 거친 뒤, 이 서비스에
물어보면 src/build_ontology_graph.py의 assess_risk()를 그대로 호출해서 원자재
공급사/인바운드 계약/제품/아웃바운드 계약(완제품 판매처)까지 한 번에 전개해서 돌려준다.

fastapi-ai(멀티에이전트 본체) 안에 넣지 않고 별도 프로세스로 둔 이유: 그 프로젝트 자체
원칙("FastAPI는 ERP 데이터에 직접 접근하지 않는다, Spring이 조회해서 넘겨준 값만 쓴다")과
데이터 소스 경계를 지키기 위함 — 이 서비스가 읽는 CSV는 Spring의 라이브 DB와는 분리된
스냅샷이라는 점을 명시적으로 유지한다. 2단계(멀티에이전트 재정비)에서 Spring/FastAPI가
이 서비스를 호출하도록 연결한다.
"""
import csv
import json
import os
import sys

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE_DIR, "src"))

from build_ontology_graph import build_graph, assess_risk, ERP_DIR, OUTBOUND_DIR  # noqa: E402
from material_category_mapper import to_categories  # noqa: E402

CONTRACTS_CSV_COLUMNS = [
    "contract_id", "contract_number", "supplier_id", "material_id", "contract_name",
    "contract_status", "effective_date", "expiration_date", "document_id",
    "document_source", "document_path", "contract_role", "supplier_approval_status",
    "indexed_at", "updated_at",
]
SUPPLIER_MATERIALS_CSV_COLUMNS = [
    "supplier_material_id", "supplier_id", "material_id", "supply_share_ratio",
    "lead_time_days", "minimum_order_quantity", "approved_status", "priority_rank",
    "is_alternative", "valid_from", "valid_to", "contract_id",
]


class ContractAppendFields(BaseModel):
    erp_contract_id: str
    contract_number: str
    erp_supplier_id: str
    erp_material_id: str
    contract_name: str
    contract_status: str
    effective_date: str
    expiration_date: str | None = None
    document_id: str
    document_source: str
    document_path: str
    contract_role: str
    supplier_approval_status: str
    indexed_at: str
    updated_at: str


class SupplierMaterialAppendFields(BaseModel):
    erp_supplier_material_id: str
    erp_supplier_id: str
    erp_material_id: str
    supply_share_ratio: float
    lead_time_days: int
    minimum_order_quantity: float
    approved_status: str
    priority_rank: int
    is_alternative: bool
    valid_from: str
    valid_to: str | None = None


class AppendContractRequest(BaseModel):
    contract: ContractAppendFields
    supplier_material: SupplierMaterialAppendFields | None = None


OUTBOUND_CONTRACTS_CSV_COLUMNS = [
    "contract_id", "product_id", "customer_id", "seller", "quantity_gwh",
    "unit_price_usd_kwh", "penalty_pct", "line_stop_charge_usd", "line_stop_charge_krw",
    "delivery_lead_time_days", "contract_language",
]


class OutboundContractAppendFields(BaseModel):
    erp_outbound_contract_id: str
    erp_product_id: str
    erp_customer_id: str
    seller: str
    quantity_gwh: float
    unit_price_usd_kwh: float
    penalty_pct: float
    line_stop_charge_usd: float | None = None
    line_stop_charge_krw: float | None = None
    delivery_lead_time_days: int
    contract_language: str


class AppendOutboundContractRequest(BaseModel):
    outbound_contract: OutboundContractAppendFields


INVENTORY_SNAPSHOTS_CSV_COLUMNS = [
    "inventory_snapshot_id", "material_id", "warehouse_id", "on_hand_quantity",
    "reserved_quantity", "blocked_quantity", "quality_hold_quantity", "safety_stock_quantity",
    "snapshot_at", "is_current", "source_unit", "normalized_unit", "data_quality_flag",
]
MATERIAL_CONSUMPTIONS_CSV_COLUMNS = [
    "consumption_id", "material_id", "plant_code", "average_daily_usage",
    "calculation_window_days", "calculated_at", "is_current", "data_quality_flag",
]


class InventorySnapshotAppendFields(BaseModel):
    erp_inventory_snapshot_id: str
    erp_material_id: str
    erp_warehouse_id: str
    on_hand_quantity: float
    reserved_quantity: float
    blocked_quantity: float
    quality_hold_quantity: float
    safety_stock_quantity: float
    snapshot_at: str
    source_unit: str
    normalized_unit: str
    data_quality_flag: str


class AppendInventorySnapshotRequest(BaseModel):
    inventory_snapshot: InventorySnapshotAppendFields


class MaterialConsumptionAppendFields(BaseModel):
    erp_consumption_id: str
    erp_material_id: str
    plant_code: str
    average_daily_usage: float
    calculation_window_days: int
    calculated_at: str
    data_quality_flag: str


class AppendMaterialConsumptionRequest(BaseModel):
    material_consumption: MaterialConsumptionAppendFields


app = FastAPI(title="KG Resolver Service")

_state: dict = {"G": None, "inventory": None, "consumption": None}


def _load_graph() -> None:
    G, inventory, consumption = build_graph()
    _state["G"], _state["inventory"], _state["consumption"] = G, inventory, consumption


@app.on_event("startup")
def _on_startup() -> None:
    _load_graph()


@app.post("/admin/reload")
def reload_graph() -> dict:
    """ERP mock CSV가 바뀐 뒤 그래프를 수동으로 다시 만든다."""
    _load_graph()
    return {
        "status": "reloaded",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


@app.post("/admin/append_contract")
def append_contract(request: AppendContractRequest) -> dict:
    """Spring이 새 계약을 생성할 때 호출한다(계약서 업로드 → CTR-XXX 자동 발급 작업).

    Spring의 backend-review는 Docker(WSL2)에서 도는데, Docker는 이 프로젝트가 있는
    Google Drive 가상 드라이브(G:) 안의 파일을 못 읽어서 여기 ERP_DIR을 직접 마운트할 수
    없다 — 그래서 Spring이 자기 쪽 CSV(별도 로컬 복사본)에 쓴 것과 같은 내용을 이 엔드포인트
    호출로 kg_service의 진짜 소스(ERP_DIR, 일반 Python 파일 I/O라 Google Drive 드라이브를
    문제없이 읽고 쓸 수 있음)에도 반영한다. 반영 후 그래프까지 바로 다시 만든다.
    """
    c = request.contract
    contract_row = [
        c.erp_contract_id, c.contract_number, c.erp_supplier_id, c.erp_material_id,
        c.contract_name, c.contract_status, c.effective_date, c.expiration_date or "",
        c.document_id, c.document_source, c.document_path, c.contract_role,
        c.supplier_approval_status, c.indexed_at, c.updated_at,
    ]
    _append_csv_row(os.path.join(ERP_DIR, "04_contracts.csv"), contract_row)

    if request.supplier_material is not None:
        sm = request.supplier_material
        sm_row = [
            sm.erp_supplier_material_id, sm.erp_supplier_id, sm.erp_material_id,
            sm.supply_share_ratio, sm.lead_time_days, sm.minimum_order_quantity,
            sm.approved_status, sm.priority_rank, str(sm.is_alternative).lower(),
            sm.valid_from, sm.valid_to or "", c.erp_contract_id,
        ]
        _append_csv_row(os.path.join(ERP_DIR, "05_supplier_materials.csv"), sm_row)

    _load_graph()
    return {
        "status": "appended",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


@app.post("/admin/append_outbound_contract")
def append_outbound_contract(request: AppendOutboundContractRequest) -> dict:
    """Spring이 새 아웃바운드 계약(CTR-OUT-XXX)을 생성할 때 호출한다. append_contract()와 같은
    이유(Docker가 Google Drive 가상 드라이브를 못 읽는 문제 우회)로 kg_service가 자기 쪽
    outbound_contracts.csv(OUTBOUND_DIR, 일반 파일 I/O)에 반영하고 그래프를 다시 만든다.

    알려진 한계: 이 엔드포인트는 계약 행만 append한다. product_id/customer_id가 products.csv/
    customers.csv에 이미 있는 기존 제품·고객사 조합이어야 한다 — 완전히 새로운 제품/고객사
    마스터 데이터 자체를 만드는 기능은 아니다(이번 스코프 밖).
    """
    c = request.outbound_contract
    contract_row = [
        c.erp_outbound_contract_id, c.erp_product_id, c.erp_customer_id, c.seller,
        c.quantity_gwh, c.unit_price_usd_kwh, c.penalty_pct,
        c.line_stop_charge_usd if c.line_stop_charge_usd is not None else "",
        c.line_stop_charge_krw if c.line_stop_charge_krw is not None else "",
        c.delivery_lead_time_days, c.contract_language,
    ]
    _append_csv_row(os.path.join(OUTBOUND_DIR, "outbound_contracts.csv"), contract_row)

    _load_graph()
    return {
        "status": "appended",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


@app.post("/admin/append_inventory_snapshot")
def append_inventory_snapshot(request: AppendInventorySnapshotRequest) -> dict:
    """Spring이 재고 스냅샷을 갱신할 때 호출한다(2026-08-01 신설).

    append_contract()와 같은 이유(Docker가 Google Drive 가상 드라이브를 못 읽는 문제 우회)로
    kg_service가 자기 쪽 06_inventory_snapshots.csv(일반 파일 I/O)에도 반영한다.

    알려진 한계였던 부분(2026-07-31): 지금까지는 Spring이 /admin/reload만 불러서, kg_service는
    똑같은 옛날 CSV를 다시 읽을 뿐 새 재고 숫자가 전혀 반영되지 않았다. 이 엔드포인트가 그
    빠진 "실제 데이터 전달" 통로다 — ErpAdminService.upsertInventorySnapshot()과 동일하게
    같은 (material_id, warehouse_id)의 기존 is_current 행을 false로 내리고 새 행을 추가한다.
    """
    s = request.inventory_snapshot
    new_row = [
        s.erp_inventory_snapshot_id, s.erp_material_id, s.erp_warehouse_id,
        s.on_hand_quantity, s.reserved_quantity, s.blocked_quantity,
        s.quality_hold_quantity, s.safety_stock_quantity, s.snapshot_at, "true",
        s.source_unit, s.normalized_unit, s.data_quality_flag,
    ]
    path = os.path.join(ERP_DIR, "06_inventory_snapshots.csv")
    _mark_not_current_and_append(
        path,
        match=lambda row_dict: (
            row_dict["material_id"] == s.erp_material_id
            and row_dict["warehouse_id"] == s.erp_warehouse_id
        ),
        new_row=new_row,
    )

    _load_graph()
    return {
        "status": "appended",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


@app.post("/admin/append_material_consumption")
def append_material_consumption(request: AppendMaterialConsumptionRequest) -> dict:
    """Spring이 소비량을 갱신할 때 호출한다. append_inventory_snapshot()과 같은 이유·같은 패턴."""
    c = request.material_consumption
    new_row = [
        c.erp_consumption_id, c.erp_material_id, c.plant_code,
        c.average_daily_usage, c.calculation_window_days, c.calculated_at, "true",
        c.data_quality_flag,
    ]
    path = os.path.join(ERP_DIR, "07_material_consumptions.csv")
    _mark_not_current_and_append(
        path,
        match=lambda row_dict: (
            row_dict["material_id"] == c.erp_material_id
            and row_dict["plant_code"] == c.plant_code
        ),
        new_row=new_row,
    )

    _load_graph()
    return {
        "status": "appended",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


def _mark_not_current_and_append(path: str, match, new_row: list) -> None:
    """match(row_dict)가 True이고 is_current=true인 기존 행을 false로 내린 뒤 new_row를 추가한다.

    스냅샷 테이블(재고/소비량)은 append_contract()류처럼 단순 append가 아니라, Spring의
    upsert 방식(기존 current 내리고 새 행 추가)과 CSV도 맞춰야 check_inventory()가 항상
    is_current=true인 최신 한 줄만 보게 된다.
    """
    with open(path, encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        rows = [list(row) for row in reader]

    is_current_idx = header.index("is_current")
    for row in rows:
        row_dict = dict(zip(header, row))
        if match(row_dict) and row_dict["is_current"].strip().lower() == "true":
            row[is_current_idx] = "false"
    rows.append([str(value) for value in new_row])

    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(rows)


def _append_csv_row(path: str, row: list) -> None:
    with open(path, "a", encoding="utf-8", newline="") as f:
        csv.writer(f).writerow(row)


@app.get("/health")
def health() -> dict:
    if _state["G"] is None:
        raise HTTPException(status_code=503, detail="graph not loaded")
    return {
        "status": "ok",
        "nodes": _state["G"].number_of_nodes(),
        "edges": _state["G"].number_of_edges(),
    }


@app.get("/resolve")
def resolve(
    country: str = Query(..., description="ISO 국가코드, 예: CL"),
    affected_materials: str = Query(
        ...,
        description='LLM이 추출한 자유텍스트 자재 리스트(JSON), 예: ["lithium", "cobre"]',
    ),
) -> dict:
    if _state["G"] is None:
        raise HTTPException(status_code=503, detail="graph not loaded")

    try:
        materials = json.loads(affected_materials)
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=400, detail="affected_materials must be a JSON list string"
        ) from exc
    if not isinstance(materials, list):
        raise HTTPException(
            status_code=400, detail="affected_materials must be a JSON list string"
        )

    # to_categories()는 원래 LLM 원본 컬럼(파이썬 리스트의 문자열 표현)을 받게 설계돼
    # 있어서(ast.literal_eval), 여기서 받은 리스트를 같은 문자열 표현으로 되돌려서 넘긴다.
    categories = to_categories(str(materials))
    if not categories:
        return {
            "matched": False,
            "country": country,
            "affected_materials": materials,
            "results": [],
        }

    results = [
        assess_risk(
            _state["G"],
            _state["inventory"],
            _state["consumption"],
            country=country,
            material_category=category,
        )
        for category in categories
    ]

    return {
        "matched": True,
        "country": country,
        "affected_materials": materials,
        "results": results,
    }
