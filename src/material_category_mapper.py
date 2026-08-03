"""
뉴스 이벤트의 자유텍스트 affected_material(JSON 리스트 문자열)을 ERP material_category로 매핑.

`assess_risk(G, ..., material_category=...)`(build_ontology_graph.py)는 ERP의
8개 표준 카테고리(LITHIUM/NICKEL/COBALT/GRAPHITE/MANGANESE/COPPER/ALUMINUM/
RARE_EARTH, `01_materials.csv` 기준) 중 하나를 정확히 받아야 그래프 순회가
된다. 반면 뉴스 이벤트의 `affected_material`은 LLM이 뽑은 자유텍스트 리스트라
`["cobre", "iron ore"]`처럼 여러 언어·여러 광물이 섞여 나온다.

이 모듈은 그 리스트를 훑어서 ERP 8개 카테고리 중 실제로 매칭되는 것만 뽑는다.
금/은/철광석/우라늄/석탄처럼 이 배터리 공급망 ERP가 애초에 안 다루는 광물은
매칭 안 시키는 게 맞음 — 억지로 끼워맞추면 존재하지 않는 공급망을 조회하게 됨.
"""
from __future__ import annotations

import ast

# ERP 8개 표준 카테고리 (backend/data/ERP_data/spring-csv/01_materials.csv 기준)
ERP_MATERIAL_CATEGORIES = {
    "LITHIUM", "NICKEL", "COBALT", "GRAPHITE", "MANGANESE",
    "COPPER", "ALUMINUM", "RARE_EARTH",
}

# 자유텍스트 토큰(소문자) -> ERP 카테고리. 영어/스페인어 변형만 실측 상위 빈도 기준 커버.
_TOKEN_TO_CATEGORY: dict[str, str] = {
    "lithium": "LITHIUM", "litio": "LITHIUM", "lithium carbonate": "LITHIUM",
    "lithium hydroxide": "LITHIUM",
    "nickel": "NICKEL", "nickel sulfate": "NICKEL",
    "cobalt": "COBALT", "cobalt ore": "COBALT", "cobalt sulfate": "COBALT",
    "graphite": "GRAPHITE", "natural graphite": "GRAPHITE", "synthetic graphite": "GRAPHITE",
    "grafito": "GRAPHITE",
    "manganese": "MANGANESE", "manganeso": "MANGANESE",
    "copper": "COPPER", "cobre": "COPPER", "copper concentrate": "COPPER",
    "copper ore": "COPPER",
    "aluminum": "ALUMINUM", "aluminium": "ALUMINUM", "aluminio": "ALUMINUM",
    "rare earth minerals": "RARE_EARTH", "rare earths": "RARE_EARTH",
    "rare earth metals": "RARE_EARTH", "rare earth oxides": "RARE_EARTH",
    "rare earth magnets": "RARE_EARTH", "rare earth elements": "RARE_EARTH",
    "neodymium": "RARE_EARTH", "neodymium oxide": "RARE_EARTH",
    "scandium": "RARE_EARTH", "dysprosium": "RARE_EARTH", "terbium": "RARE_EARTH",
}


def to_categories(raw: str | None) -> list[str]:
    """affected_material JSON 리스트 문자열 -> 매칭되는 ERP 카테고리 리스트(중복 제거, 정렬).

    매칭되는 게 하나도 없으면 빈 리스트 반환(이 ERP가 안 다루는 광물이라는 뜻 —
    억지로 추정하지 않음).
    """
    if not raw or not isinstance(raw, str):
        return []

    try:
        items = ast.literal_eval(raw)
    except (ValueError, SyntaxError):
        return []

    if not isinstance(items, list):
        return []

    matched = set()
    for item in items:
        if not isinstance(item, str):
            continue
        key = item.strip().lower()
        if key in _TOKEN_TO_CATEGORY:
            matched.add(_TOKEN_TO_CATEGORY[key])

    return sorted(matched)
