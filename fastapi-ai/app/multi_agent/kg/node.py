import logging

from app.core.config import get_settings
from app.multi_agent.graph.state import BriefingState
from app.services.kg_service_client import resolve_kg_context

logger = logging.getLogger(__name__)

# 게이트를 우회했을 때 warnings에 남기는 문구. 이 문자열이 평가에 붙어 있으면
# "KG가 확정한 결과"가 아니라는 뜻이다 — 화면·감사에서 구분할 수 있어야 한다.
KG_GATE_BYPASSED_WARNING = (
    "KG 게이트를 우회한 실행입니다(KG_GATE_ENABLED=false). "
    "KG가 확정한 공급사 없이 ERP 노드를 태웠으므로 공급사 지목의 근거가 없습니다."
)


def extract_kg_fields(response: dict) -> dict:
    """kg_service /resolve 응답을 BriefingState 필드로 변환한다."""

    results = response.get("results", [])

    affected_suppliers: set[str] = set()
    affected_contract_ids: set[str] = set()
    affected_outbound_contract_ids: set[str] = set()
    alternative_suppliers: list[dict] = []
    evidence_paths: list[str] = []
    impact_scores: list[float] = []
    shortage_detected = False

    for result in results:
        affected_suppliers.update(
            result.get("affected_suppliers", []),
        )
        affected_contract_ids.update(
            result.get("affected_inbound_contracts", []),
        )
        affected_outbound_contract_ids.update(
            result.get("affected_outbound_contracts", []),
        )
        alternative_suppliers.extend(
            result.get("alternative_suppliers", []),
        )

        evidence_path = result.get("evidence_path")
        if evidence_path:
            evidence_paths.append(evidence_path)

        impact_score = result.get("impact_score")
        if impact_score:
            impact_scores.append(impact_score)

        inventory_checks = result.get(
            "inventory_checks",
            {},
        )
        if any(
            check.get("status") == "SHORTAGE"
            for check in inventory_checks.values()
        ):
            shortage_detected = True

    return {
        "kg_matched": response.get("matched", False),
        "kg_shortage_detected": shortage_detected,
        "kg_affected_suppliers": sorted(affected_suppliers),
        "kg_affected_contract_ids": sorted(affected_contract_ids),
        "kg_affected_outbound_contract_ids": sorted(
            affected_outbound_contract_ids,
        ),
        "kg_alternative_suppliers": alternative_suppliers,
        "kg_impact_score": (
            max(impact_scores) if impact_scores else None
        ),
        "kg_evidence_paths": evidence_paths,
    }


def build_no_shortage_briefing(
    state: BriefingState,
    kg_fields: dict,
) -> dict:
    """게이트 미통과(매칭無 또는 재고 충분) 시 LLM 없이 경량 종료 상태를 만든다."""

    if not kg_fields["kg_matched"]:
        reason = (
            "KG 그래프에서 뉴스의 영향 원자재/국가와 매칭되는 "
            "공급망 경로를 찾지 못했습니다."
        )
    else:
        reason = (
            "관련 공급사는 있으나 현재 재고가 안전재고 이상으로 "
            "충분해 계약/RAG 조회로 확산하지 않았습니다."
        )

    return {
        "erp_assessment": {},
        "erp_reassessment": {},
        "contract_assessment": {},
        "contract_findings": [],
        "procurement_risk_score": 0,
        "procurement_risk_level": "normal",
        "risk_reasons": [reason],
        "briefing": f"[정상] {reason}",
        "recommended_actions": ["별도 조치가 필요하지 않습니다."],
        "llm_used": False,
        "llm_error": None,
        "review_passed": True,
        "warnings": [],
        "error_owner": None,
    }


def analyze_kg_context_node(
    state: BriefingState,
) -> dict:
    """뉴스에서 추출한 country+affected_materials로 KG를 조회해 게이트를 판정한다."""

    country = state.get("country")
    affected_materials = state.get(
        "affected_materials",
        [],
    )

    if not country or not affected_materials:
        response: dict = {
            "matched": False,
            "country": country,
            "affected_materials": affected_materials,
            "results": [],
        }
    else:
        response = resolve_kg_context(
            country=country,
            affected_materials=affected_materials,
        )

    kg_fields = extract_kg_fields(response)

    result = {
        "kg_context": response,
        **kg_fields,
    }

    if kg_fields["kg_shortage_detected"]:
        return result

    # 게이트 미통과. 평소에는 여기서 경량 종료하지만, KG_GATE_ENABLED=false면 통과시켜
    # 뒷단(ERP·계약·위험도·브리핑)을 그대로 태운다 — kg_service가 없을 때 파이프라인을
    # 확인하기 위한 통로다. 우회 사실은 warnings에 남겨 결과와 함께 따라다니게 한다.
    if not get_settings().kg_gate_enabled:
        logger.warning(
            "KG 게이트 우회 (country=%s, affected_materials=%s, matched=%s) "
            "— KG_GATE_ENABLED=false",
            country,
            affected_materials,
            kg_fields["kg_matched"],
        )
        result["kg_gate_bypassed"] = True
        result["warnings"] = [
            *state.get("warnings", []),
            KG_GATE_BYPASSED_WARNING,
        ]
        return result

    result.update(
        build_no_shortage_briefing(
            state,
            kg_fields,
        ),
    )

    return result
