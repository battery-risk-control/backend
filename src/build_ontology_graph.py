"""
경량 온톨로지 그래프 (CLAUDE.md 결정사항 10번).

역할 분리 (2026-07-24 세션에서 팀원 백엔드 repo 확인 후 재설계):
  - ERP(backend/data/ERP_data/spring-csv)  : "재고가 부족한가?" 숫자 판단만.
  - 이 그래프(networkx)                     : 리스크 이벤트 -> 어떤 공급사/계약이
                                               걸리는지, 재고 부족이 실제로 확인되면
                                               그게 어떤 제품/고객사까지 번지는지
                                               "연결 구조"만 찾아준다.
  - RAG(ChromaDB, 팀원 구현)                : 그래프가 찾아낸 계약들의 실제 조항
                                               (배상금 %, 불가항력 예외) 텍스트 조회.
그래프 자체는 계약 조항 내용을 모른다 — 그건 RAG의 일이다. 그래프는 그저
"어떤 contract_id를 RAG에 물어봐야 하는지" 좁혀주는 역할만 한다.

시나리오 (사용자 정의):
  리튬 광산 매몰(뉴스) -> ERP 재고 확인 -> 부족하면
    ① 원자재 공급사 계약(인바운드) 조회 대상 확정 (불가항력/배상금 조항 RAG 조회용)
    ② 그 자재를 쓰는 배터리 제품 -> 완성차 고객사까지 전파
       (납품 지연 배상금 조항 RAG 조회 대상 확정, 아웃바운드)
  재고가 충분하면 굳이 계약/RAG까지 확산시키지 않는다 (뉴스 하나로 전체 계약을
  다 훑는 낭비를 막는 게 애초에 트리아지 필터를 만든 이유와 같은 맥락).

인바운드(공급사->원자재->LG엔솔)는 팀원 백엔드의 진짜 ERP 마스터 데이터
(backend/data/ERP_data/spring-csv)에서 그대로 읽는다 — mock 아님, 파싱 불필요.
아웃바운드(LG엔솔->배터리제품->완성차고객사)는 ERP에 대응하는 테이블이 아예 없어서
(고객사/제품 개념 자체가 백엔드에 없음) data_ref/outbound_mock/의 CSV(가상 데이터,
2026-07-28 확장 — 아래 "아웃바운드 확장" 절 참고)를 쓴다 — 이 부분만 여전히
가상 데이터라는 점을 유의할 것.

## 아웃바운드 확장 + 신규 기능 3종 (2026-07-28)

기존엔 `rag_dataset/outbound_sales/*.txt` 자유텍스트를 정규식으로 파싱해서 제품 5개/
고객사 8개/계약 10건뿐이었고, 하드코딩 `PRODUCT_COMPOSITION` dict라 COPPER/ALUMINUM/
RARE_EARTH 카테고리는 그 카테고리를 쓰는 제품 자체가 없어서 아웃바운드에 도달 불가능
했다. `data_prep/generate_outbound_master_data.py`로 `data_ref/outbound_mock/`에
제품 16종/고객사 22개/계약 27건 CSV를 생성해서 대체했다(상세 근거는 그 폴더의
README.md). 백엔드(`backend/`) 멀티에이전트 코드(`erp_agent.py` 등)를 전부 읽고
확인한 결과 SPOF류(공급사 의존도)는 이미 구현돼 있어 중복이지만, 제품/고객사/
아웃바운드 개념은 백엔드 어디에도 없어서 이 그래프만의 유일한 영역이다 — 그래서
이번에 추가한 3가지 신규 기능도 전부 이 아웃바운드 영역 또는 그래프 traversal
자체에 기반한다:
  ① `build_evidence_path()` — assess_risk() 결과를 사람이 읽는 근거 경로 문자열로.
  ② `recommend_alternative_suppliers()` — 재고 부족 확정 시에만, 같은 자재를
     대는 다른 나라 공급사를 리스크 낮은 순으로 추천.
  ③ `run_risk_assessment.py`의 `detect_compound_risk()` — 서로 다른 이벤트가
     같은 아웃바운드 계약을 30일 이내에 건드리면 복합 리스크로 플래그.

## Magnitude 단위 통일 (2026-07-28)

기존엔 인바운드/아웃바운드 계약 규모(magnitude)가 서로 다른 임의 스케일이었다
— 인바운드는 `supply_share_ratio × 1e9`(근거 없는 상수), 아웃바운드는 GWh 단가
계산에 kWh 단위 환산(×1,000,000)이 빠진 채 KRW 환산 상수만 곱해서 실제보다
100만 배 축소된 값이었다(둘 다 이번에 수정). 이제 둘 다 **USD**로 통일한다:

  - 인바운드: `backend/data/ERP_data/spring-csv/08_purchase_orders.csv`
    (통화 USD/EUR/KRW 혼재) + `09_purchase_order_items.csv`(ordered_quantity,
    unit_price, contract_id)로 계약당 실제 발주 누적액(`ordered_quantity ×
    unit_price`, FX_TO_USD로 환산)을 합산해서 씀 — 더 이상 임의 상수 아님.
  - 아웃바운드: `quantity_gwh × 1,000,000 × unit_price_usd_kwh`(GWh->kWh 환산
    버그 수정) + line-stop 배상금(KRW/USD 어느 쪽이든 FX_TO_USD로 USD 환산).

**여전히 남는 한계**: 인바운드는 "그 계약으로 실제 찍힌 발주 누적액"이고
아웃바운드는 "계약서에 명시된 총 계약기간 커버리지 총액"이라 성격이 다르다
(전자는 최근 발주 스냅샷, 후자는 계약 전체 기간 총 물량) — 완벽한 동질 비교는
아니지만 최소한 같은 통화·같은 크기 단위로는 맞아서, 이전처럼 한쪽이 자릿수
자체가 잘못돼 있는 상태보다는 훨씬 낫다.

FX_TO_USD의 환율은 정밀 API 연동이 아니라 기존 코드 곳곳에 쓰이던 1400
KRW/USD 관례와 같은 수준의 고정 근사치다(EUR도 동일 수준 근사).
"""
import os
import pickle
import argparse

import pandas as pd
import networkx as nx

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 이 파일은 backend/src/ 안에 있으므로 BASE_DIR은 backend/ 자체다(2026-08-03, kg_service를
# backend 저장소로 이전하며 "backend/backend/..." 중복 경로 버그 방지 차 조정 — 예전엔 이
# 스크립트가 프로젝트 루트에 있어서 BASE_DIR/backend/...였다). 기본값은 이 저장소 안의
# data/ERP_data/spring-csv 기준이지만, Docker가 실제로 seed하는 로컬 clone(예:
# C:\backend-review)과 같은 데이터를 보도록 띄우고 싶을 때는 KG_ERP_DIR/KG_OUTBOUND_DIR
# 환경변수로 덮어쓴다. "두 사본이 따로 논다" 문제(2026-07-31)의 임시 완화책 — 근본 해결(ERP
# 데이터 자체를 git으로 통일)은 팀 브랜치 정책 합의가 필요해 별도로 남겨둔다.
ERP_DIR = os.environ.get(
    "KG_ERP_DIR",
    os.path.join(BASE_DIR, "data", "ERP_data", "spring-csv"),
)
OUTBOUND_DIR = os.environ.get(
    "KG_OUTBOUND_DIR",
    os.path.join(BASE_DIR, "data_ref", "outbound_mock"),
)
MODELS_DIR = os.path.join(BASE_DIR, "models")
RESULTS_DIR = os.path.join(BASE_DIR, "results", "ontology")

COMPANY = "LG에너지솔루션"

# ERP material_category -> 그래프에서 쓰는 표준 카테고리 (그대로 사용, 매핑 불필요)
# LITHIUM / NICKEL / COBALT / GRAPHITE / MANGANESE / COPPER / ALUMINUM / RARE_EARTH

# 고정 근사 환율 (정밀 환율 API 연동 아님, 기존 1400 KRW/USD 관례와 같은 수준)
FX_TO_USD = {"USD": 1.0, "KRW": 1 / 1400, "EUR": 1.08}


# ---------------------------------------------------------------------------
# 인바운드: 진짜 ERP 마스터 데이터 (파싱 없음, CSV 컬럼 그대로)
# ---------------------------------------------------------------------------

def load_erp_tables():
    materials = pd.read_csv(os.path.join(ERP_DIR, "01_materials.csv"))
    suppliers = pd.read_csv(os.path.join(ERP_DIR, "02_suppliers.csv"))
    contracts = pd.read_csv(os.path.join(ERP_DIR, "04_contracts.csv"))
    supplier_materials = pd.read_csv(os.path.join(ERP_DIR, "05_supplier_materials.csv"))
    inventory = pd.read_csv(os.path.join(ERP_DIR, "06_inventory_snapshots.csv"))
    consumption = pd.read_csv(os.path.join(ERP_DIR, "07_material_consumptions.csv"))
    contract_values_usd = load_contract_values_usd()
    return materials, suppliers, contracts, supplier_materials, inventory, consumption, contract_values_usd


def load_contract_values_usd():
    """계약(contract_id)별 실제 발주 누적액을 USD로 환산해 합산한다.

    08_purchase_orders.csv(통화 USD/EUR/KRW 혼재)의 currency를 09_purchase_order_items.csv
    (ordered_quantity, unit_price, contract_id)에 조인해서 FX_TO_USD로 통일한다.
    """
    orders = pd.read_csv(os.path.join(ERP_DIR, "08_purchase_orders.csv"))
    items = pd.read_csv(os.path.join(ERP_DIR, "09_purchase_order_items.csv"))
    merged = items.merge(orders[["purchase_order_id", "currency"]], on="purchase_order_id", how="left")
    fx_rate = merged["currency"].map(FX_TO_USD)
    merged["value_usd"] = merged["ordered_quantity"] * merged["unit_price"] * fx_rate
    return merged.groupby("contract_id")["value_usd"].sum().to_dict()


def check_inventory(material_id, inventory_df, consumption_df):
    """
    F1 ERP Context(Spring, /api/v1/erp/context)와 같은 소스 CSV로 계산한
    경량 재고 부족 판정. Spring 쪽 정식 계산을 대체하는 게 아니라, 이 그래프
    스크립트를 서버 없이 단독으로 데모/테스트할 수 있게 하기 위한 축약판.
    """
    snap = inventory_df[(inventory_df["material_id"] == material_id) & (inventory_df["is_current"])]
    if snap.empty:
        return {"status": "UNKNOWN", "reason": "NO_CURRENT_SNAPSHOT"}

    available = (
        snap["on_hand_quantity"] - snap["reserved_quantity"]
        - snap["blocked_quantity"] - snap["quality_hold_quantity"]
    ).sum()
    safety_stock = snap["safety_stock_quantity"].sum()

    cons = consumption_df[(consumption_df["material_id"] == material_id) & (consumption_df["is_current"])]
    avg_daily_usage = cons["average_daily_usage"].sum() if not cons.empty else 0

    stock_days = round(available / avg_daily_usage, 1) if avg_daily_usage > 0 else None
    shortage = available < safety_stock

    return {
        "status": "SHORTAGE" if shortage else "OK",
        "available_quantity": int(available),
        "safety_stock_quantity": int(safety_stock),
        "avg_daily_usage": float(avg_daily_usage),
        "stock_days": stock_days,
    }


# ---------------------------------------------------------------------------
# 아웃바운드: data_ref/outbound_mock CSV (여전히 mock — ERP에 대응 테이블 없음,
# 상세 근거는 data_ref/outbound_mock/README.md 참고)
# ---------------------------------------------------------------------------

def load_outbound_tables():
    products = pd.read_csv(os.path.join(OUTBOUND_DIR, "products.csv"))
    composition = pd.read_csv(os.path.join(OUTBOUND_DIR, "product_composition.csv"))
    customers = pd.read_csv(os.path.join(OUTBOUND_DIR, "customers.csv"))
    contracts = pd.read_csv(os.path.join(OUTBOUND_DIR, "outbound_contracts.csv"))
    return products, composition, customers, contracts


# ---------------------------------------------------------------------------
# 그래프 조립
# ---------------------------------------------------------------------------

def build_graph():
    materials, suppliers, contracts, supplier_materials, inventory, consumption, contract_values_usd = load_erp_tables()
    products, composition, customers, outbound_contracts = load_outbound_tables()

    G = nx.MultiDiGraph()
    G.add_node(COMPANY, type="Company")

    for _, m in materials.iterrows():
        G.add_node(m["material_id"], type="Material", name=m["material_name"], category=m["material_category"])

    for _, s in suppliers.iterrows():
        country = s["country_code"]
        G.add_node(s["supplier_id"], type="Supplier", name=s["supplier_name"],
                   country=country, risk_level=s["risk_level"], feoc_status=s["feoc_status"])
        if country not in G:
            G.add_node(country, type="Country")
        G.add_edge(country, s["supplier_id"], relation="LOCATED_IN")

    contracts_by_id = contracts.set_index("contract_id").to_dict("index")

    for _, sm in supplier_materials.iterrows():
        contract_id = sm["contract_id"]
        contract_meta = contracts_by_id.get(contract_id, {})
        edge_attrs = {
            "contract_id": contract_id,
            "supply_share_ratio": sm["supply_share_ratio"],
            "lead_time_days": sm["lead_time_days"],
            "approved_status": sm["approved_status"],
            "contract_status": contract_meta.get("contract_status"),
            "document_id": contract_meta.get("document_id"),
            "document_path": contract_meta.get("document_path"),
            "contract_value_usd": contract_values_usd.get(contract_id, 0.0),
        }
        G.add_edge(sm["supplier_id"], sm["material_id"], relation="SUPPLIES", **edge_attrs)
        G.add_edge(sm["material_id"], COMPANY, relation="INBOUND_CONTRACT", **edge_attrs)

    for _, p in products.iterrows():
        G.add_node(p["product_id"], type="Product", name=p["name_en"], name_kr=p["name_kr"],
                   product_line=p["product_line"])
        G.add_edge(COMPANY, p["product_id"], relation="MANUFACTURES")

    for _, comp in composition.iterrows():
        G.add_edge(comp["product_id"], comp["material_category"], relation="USES_CATEGORY")

    for _, cust in customers.iterrows():
        G.add_node(cust["customer_id"], type="Customer", name=cust["name_en"], name_kr=cust["name_kr"],
                   country=cust["country_code"])

    for _, c in outbound_contracts.iterrows():
        G.add_edge(c["product_id"], c["customer_id"], relation="OUTBOUND_CONTRACT", **c.to_dict())

    return G, inventory, consumption


def _contract_magnitude(edge_data):
    """계약 규모를 USD 스칼라로 통일한 값 (2026-07-28 단위 통일, 위 docstring 참고).

    인바운드는 실제 발주 누적액(contract_value_usd), 아웃바운드는 계약서에 명시된
    총 계약기간 커버리지 총액 — 둘 다 USD지만 성격이 다른 근사치라는 점은 남는다.
    """
    if "contract_value_usd" in edge_data:
        return float(edge_data.get("contract_value_usd") or 0)

    qty_kwh = (edge_data.get("quantity_gwh") or 0) * 1_000_000  # GWh -> kWh
    unit_price_usd = edge_data.get("unit_price_usd_kwh", 0) or 0
    value_usd = qty_kwh * unit_price_usd

    # pandas가 CSV의 빈 칸을 NaN(float)으로 채우는데, NaN은 파이썬 truthy 검사를
    # 통과해버려서(bool(float('nan'))==True) "if line_stop_krw:"로는 못 거른다.
    line_stop_krw = edge_data.get("line_stop_charge_krw")
    if pd.notna(line_stop_krw) and line_stop_krw:
        line_stop_usd = line_stop_krw * FX_TO_USD["KRW"]
    else:
        line_stop_usd_raw = edge_data.get("line_stop_charge_usd")
        line_stop_usd = line_stop_usd_raw if pd.notna(line_stop_usd_raw) else 0

    return value_usd + line_stop_usd


def _truncate_list(items, limit=5, unit="개"):
    items = list(items)
    if not items:
        return "없음"
    if len(items) > limit:
        return ", ".join(items[:limit]) + f" 외 {len(items) - limit}{unit}"
    return ", ".join(items)


def _node_names(ids, G, limit=5):
    return _truncate_list([str(G.nodes[i].get("name", i)) for i in ids], limit=limit)


def build_evidence_path(G, result: dict) -> str:
    """assess_risk() 결과 -> 사람이 읽는 한국어 근거 경로 문자열.

    조기 리턴 케이스(공급사 매칭 실패/재고 충분)도 각각 다르게 문장 처리한다.
    """
    country, category = result.get("country"), result.get("material_category")
    suppliers = result.get("affected_suppliers") or []

    if not suppliers:
        return f"{country}/{category}에 해당하는 공급사가 없어 리스크 전파가 확인되지 않았습니다."

    inbound = result.get("affected_inbound_contracts") or []
    if not inbound:
        return (f"공급사 {_node_names(suppliers, G)}가 매칭되었으나, "
                "재고가 충분하여 계약/제품 단계까지 전파되지 않았습니다.")

    products = result.get("affected_products") or []
    outbound = result.get("affected_outbound_contracts") or []
    return (
        f"{country} 소재 공급사 {_node_names(suppliers, G)}"
        f" -> 인바운드 계약 {_truncate_list(inbound, unit='건')}"
        f" -> 제품 {_node_names(products, G)}"
        f" -> 아웃바운드 계약 {_truncate_list(outbound, unit='건')}"
    )


def recommend_alternative_suppliers(G, material_category, disrupted_suppliers, top_n=3) -> dict:
    """material_category를 대는 공급사 중 disrupted_suppliers에 없는 후보를
    (다른 나라 우선, risk_level 낮은 순, feoc_status 아닌 순, lead_time_days 짧은 순)으로 랭킹.

    호출 시점: assess_risk()에서 재고 부족(SHORTAGE)이 확정된 분기에서만 호출한다
    (재고가 충분하면 계약/제품까지 안 번지는 기존 철학과 일관되게).
    """
    disrupted_countries = {G.nodes[s].get("country") for s in disrupted_suppliers}
    risk_rank = {"NORMAL": 0, "WATCH": 1, "HIGH_RISK": 2}

    candidates = {}
    for u, v, data in G.edges(data=True):
        if data.get("relation") != "SUPPLIES":
            continue
        if G.nodes[v].get("category") != material_category or u in disrupted_suppliers:
            continue
        lead_time = data.get("lead_time_days")
        prev = candidates.get(u)
        if prev is None or (lead_time is not None and lead_time < prev):
            candidates[u] = lead_time

    if not candidates:
        return {"alternatives": [], "recommendation_text": "대체 공급사가 없습니다."}

    ranked = sorted(
        candidates.items(),
        key=lambda item: (
            G.nodes[item[0]].get("country") in disrupted_countries,
            risk_rank.get(G.nodes[item[0]].get("risk_level"), 1),
            bool(G.nodes[item[0]].get("feoc_status")),
            item[1] if item[1] is not None else float("inf"),
        ),
    )[:top_n]

    alternatives = [
        {
            "supplier_id": supplier_id,
            "name": G.nodes[supplier_id].get("name"),
            "country": G.nodes[supplier_id].get("country"),
            "risk_level": G.nodes[supplier_id].get("risk_level"),
            "feoc_status": G.nodes[supplier_id].get("feoc_status"),
            "lead_time_days": lead_time,
        }
        for supplier_id, lead_time in ranked
    ]
    text = "; ".join(
        f"{a['supplier_id']}({a['country']}, risk={a['risk_level']}, lead_time={a['lead_time_days']}일)"
        for a in alternatives
    )
    return {"alternatives": alternatives, "recommendation_text": f"공급 대체 후보: {text}"}


def assess_risk(G, inventory_df, consumption_df, country=None, material_category=None):
    """
    사용자가 정의한 시나리오 그대로:
      리스크 이벤트(country + material_category)
        -> 원산지 일치하는 공급사 확인
        -> 그 공급사가 대는 자재의 ERP 재고 확인
        -> 부족(SHORTAGE)할 때만 계약/제품/고객사까지 전파 (RAG 조회 대상 확정)
        -> 충분하면 공급사 정보만 반환하고 끝 (뉴스 하나로 전체 계약 다 훑지 않음)
    """
    result = {"country": country, "material_category": material_category,
              "affected_suppliers": [], "inventory_checks": {}, "affected_inbound_contracts": [],
              "affected_products": [], "affected_outbound_contracts": [], "impact_score": 0.0,
              "alternative_suppliers": [], "alternative_supplier_text": ""}

    target_materials = [n for n, d in G.nodes(data=True)
                         if d.get("type") == "Material" and d.get("category") == material_category]
    if not target_materials:
        result["evidence_path"] = build_evidence_path(G, result)
        return result

    suppliers = set()
    for mat in target_materials:
        for u, v, data in G.in_edges(mat, data=True):
            if data.get("relation") != "SUPPLIES":
                continue
            if country and G.nodes[u].get("country") != country:
                continue
            suppliers.add(u)
    result["affected_suppliers"] = sorted(suppliers)
    if not suppliers:
        result["evidence_path"] = build_evidence_path(G, result)
        return result

    any_shortage = False
    contract_edges = []
    for mat in target_materials:
        check = check_inventory(mat, inventory_df, consumption_df)
        result["inventory_checks"][mat] = check
        if check.get("status") == "SHORTAGE":
            any_shortage = True

    if not any_shortage:
        result["evidence_path"] = build_evidence_path(G, result)
        return result  # 재고 충분 -> 계약/RAG까지 안 번짐

    alt = recommend_alternative_suppliers(G, material_category, suppliers)
    result["alternative_suppliers"] = alt["alternatives"]
    result["alternative_supplier_text"] = alt["recommendation_text"]

    for mat in target_materials:
        # country 필터를 통과한 "그 공급사"의 SUPPLIES 엣지에서 바로 contract_id를 가져온다
        # (COMPANY로 들어오는 INBOUND_CONTRACT 엣지 전체를 훑으면 필터 안 걸린 다른 나라
        #  공급사의 계약까지 같이 걸리는 버그가 있었음 — 이제 발신 공급사 기준으로만 모음)
        for u, v, data in G.out_edges(list(suppliers), data=True):
            if data.get("relation") == "SUPPLIES" and v == mat and data.get("contract_id"):
                contract_edges.append(data)
    result["affected_inbound_contracts"] = sorted({c["contract_id"] for c in contract_edges})

    products = set()
    for p, d in G.nodes(data=True):
        if d.get("type") != "Product":
            continue
        for _, cat, edata in G.out_edges(p, data=True):
            if edata.get("relation") == "USES_CATEGORY" and cat == material_category:
                products.add(p)
    result["affected_products"] = sorted(products)

    outbound_edges = []
    for p in products:
        for u, v, data in G.out_edges(p, data=True):
            if data.get("relation") == "OUTBOUND_CONTRACT":
                outbound_edges.append(data)
    result["affected_outbound_contracts"] = sorted({c["contract_id"] for c in outbound_edges})

    all_edges = contract_edges + outbound_edges
    centrality = len(all_edges)
    magnitude = sum(_contract_magnitude(c) for c in all_edges)
    result["centrality"] = centrality
    result["magnitude"] = magnitude
    result["impact_score"] = centrality * magnitude
    result["evidence_path"] = build_evidence_path(G, result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--country", default="CL", help="ISO 국가코드 (ERP 기준, 예: 칠레=CL)")
    parser.add_argument("--material-category", default="LITHIUM",
                         help="LITHIUM/NICKEL/COBALT/GRAPHITE/MANGANESE/COPPER/ALUMINUM/RARE_EARTH")
    args = parser.parse_args()

    G, inventory, consumption = build_graph()
    print(f"[그래프 완성] 노드 {G.number_of_nodes()}개, 엣지 {G.number_of_edges()}개")

    os.makedirs(MODELS_DIR, exist_ok=True)
    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(os.path.join(MODELS_DIR, "ontology_graph.pkl"), "wb") as f:
        pickle.dump(G, f)
    print(f"[저장 완료] {os.path.join(MODELS_DIR, 'ontology_graph.pkl')}")

    result = assess_risk(G, inventory, consumption, country=args.country, material_category=args.material_category)

    lines = [
        f"데모 시나리오: country={result['country']}, material_category={result['material_category']}",
        f"영향받는 공급사: {result['affected_suppliers']}",
        f"재고 확인: {result['inventory_checks']}",
    ]
    if result["affected_inbound_contracts"] or result["affected_outbound_contracts"]:
        lines += [
            f"[재고 부족 확인 -> 계약/RAG 조회 대상 확정]",
            f"영향받는 인바운드 계약(원자재 공급 계약): {result['affected_inbound_contracts']}",
            f"영향받는 제품: {result['affected_products']}",
            f"영향받는 아웃바운드 계약(완성차 납품 계약): {result['affected_outbound_contracts']}",
            f"Centrality: {result.get('centrality')}, Magnitude: {result.get('magnitude'):,.0f}",
            f"ImpactScore: {result['impact_score']:,.0f}",
            f"대체 공급사 추천: {result['alternative_supplier_text']}",
        ]
    else:
        lines.append("[재고 충분 또는 데이터 없음 -> 계약/RAG 조회로 확산 안 함]")
    lines.append(f"근거 경로: {result.get('evidence_path')}")

    summary = "\n".join(lines)
    print("\n" + summary)
    with open(os.path.join(RESULTS_DIR, "graph_summary.txt"), "w", encoding="utf-8") as f:
        f.write(summary + "\n")


if __name__ == "__main__":
    main()
