from app.multi_agent.graph.state import BriefingState
from app.multi_agent.erp.adapter import (
    build_agent_erp_context,
)
from app.multi_agent.erp.schemas import ErpContext

def normalize_erp_context(raw_context: object) -> dict:
    """
    신규 Spring ERP 원본 구조와 기존 임시 구조를 모두 지원한다.
    """
    if isinstance(raw_context, ErpContext):
        return build_agent_erp_context(raw_context)

    if isinstance(raw_context, dict) and (
        "materialContext" in raw_context
        or "material_context" in raw_context
    ):
        validated_context = ErpContext.model_validate(
            raw_context
        )
        return build_agent_erp_context(
            validated_context
        )

    if isinstance(raw_context, dict):
        return raw_context

    return {}

def analyze_erp_node(state: BriefingState) -> dict:
    """
    Spring Boot가 전달한 ERP 데이터를 규칙에 따라 분석한다.

    FastAPI는 ERP DB에 직접 접근하지 않는다.
    숫자 계산은 LLM이 아니라 Python 규칙으로 처리한다.
    """
    erp_context = normalize_erp_context(
    state.get("erp_context", {})
    )

    inventory_days = erp_context.get("inventory_days")
    safety_stock_days = erp_context.get("safety_stock_days")
    open_orders = erp_context.get("open_orders", 0)
    supplier_dependency = erp_context.get("supplier_dependency")
    next_inbound_eta_days = erp_context.get("next_inbound_eta_days")

    has_alternative_supplier = erp_context.get(
        "has_alternative_supplier",
        True,
    )

    affected_contract_ids = erp_context.get(
        "contract_ids",
        [],
    )
    manual_review_required = erp_context.get(
        "manual_review_required",
        False,
    )

    calculation_warnings = erp_context.get(
    "calculation_warnings",
        [],
    )

    expected_supply_gap_days = erp_context.get(
        "expected_supply_gap_days"
    )
    findings = []

    # =========================================================
    # 1. 재고 상태 판정
    # =========================================================

    if inventory_days is None or safety_stock_days is None:
        findings.append(
            "재고 또는 안전재고 데이터가 없습니다."
        )
        inventory_status = "unknown"

    elif inventory_days < safety_stock_days:
        shortage_days = safety_stock_days - inventory_days

        findings.append(
            f"현재 재고가 안전재고 기준보다 "
            f"{shortage_days}일 부족합니다."
        )
        inventory_status = "warning"

    else:
        findings.append(
            "현재 재고가 안전재고 기준 이상입니다."
        )
        inventory_status = "normal"

    # =========================================================
    # 2. 미입고 발주 확인
    # =========================================================

    if open_orders > 0:
        findings.append(
            f"미입고 발주가 {open_orders}건 존재합니다."
        )

    # =========================================================
    # 3. 공급업체 의존도 확인
    # =========================================================

    if (
        supplier_dependency is not None
        and supplier_dependency >= 0.7
    ):
        dependency_percent = round(
            supplier_dependency * 100
        )

        findings.append(
            f"해당 공급사 의존도가 "
            f"{dependency_percent}%로 높습니다."
        )

    # =========================================================
    # 4. ERP 노출도 점수 계산
    # =========================================================

    exposure_score = 0
    stockout_before_eta = False

    # 데이터가 없으면 불확실성 위험을 반영
    if inventory_status == "unknown":
        exposure_score += 20

    # 안전재고보다 현재 재고가 적은 경우
    elif inventory_status == "warning":
        exposure_score += 40

    # 특정 공급업체에 대한 의존도가 높은 경우
    if (
        supplier_dependency is not None
        and supplier_dependency >= 0.7
    ):
        exposure_score += 25

    # 현재 재고가 다음 입고일 전에 소진되는 경우
    if (
        inventory_days is not None
        and next_inbound_eta_days is not None
        and inventory_days < next_inbound_eta_days
    ):
        stockout_before_eta = True
        exposure_score += 30

        findings.append(
            "다음 입고 예정일 전에 현재 재고가 "
            "소진될 수 있습니다."
        )

    # 대체 공급업체가 없는 경우
    if has_alternative_supplier is False:
        exposure_score += 10

        findings.append(
            "등록된 대체 공급업체가 없습니다."
        )

    # ERP 노출도 점수를 0~100으로 제한
    exposure_score = min(exposure_score, 100)

    # =========================================================
    # 5. Contract RAG Agent에게 전달할 질문 생성
    # =========================================================

    questions_for_contract_agent = []

    if stockout_before_eta:
        questions_for_contract_agent.append(
            "납기 지연 또는 공급 중단 시 적용할 수 있는 "
            "계약 조항이 있는가?"
        )

    if has_alternative_supplier is False:
        questions_for_contract_agent.append(
            "기존 계약에서 대체 조달을 제한하는 "
            "조항이 있는가?"
        )

    # =========================================================
    # 6. 분석 결과 반환
    # =========================================================

    return {
        "erp_assessment": {
        "erp_exposure_score": exposure_score,
        "inventory_status": inventory_status,
        "stockout_before_eta": stockout_before_eta,
        "has_alternative_supplier": (
            has_alternative_supplier
        ),
        "expected_supply_gap_days": (
            expected_supply_gap_days
        ),
        "manual_review_required": (
            manual_review_required
        ),
        "calculation_warnings": (
            calculation_warnings
        ),
        "findings": findings,
        },
        "affected_contract_ids": affected_contract_ids,
        "questions_for_contract_agent": (
            questions_for_contract_agent
        ),

        # 기존 코드와 테스트의 임시 호환용 필드
        "erp_findings": {
            "inventory_status": inventory_status,
            "findings": findings,
        },
    }

def recheck_erp_node(
    state: BriefingState,
) -> dict:
    """
    Contract Agent의 질문과 계약 분석 결과를 반영하여
    기존 ERP 분석 결과를 다시 평가한다.
    """
    erp_context = normalize_erp_context(
    state.get("erp_context", {})
    )

    erp_assessment = state.get(
        "erp_assessment",
        {},
    ).copy()

    questions = state.get(
        "questions_for_erp_agent",
        [],
    )

    findings = list(
        erp_assessment.get("findings", [])
    )

    score_before = erp_assessment.get(
        "erp_exposure_score",
        0,
    )
    score_after = score_before

    inventory_days = erp_context.get(
        "inventory_days"
    )

    # 계약 검토 후 Spring Boot가 제공할 수 있는 변경 입고일
    contract_adjusted_eta_days = erp_context.get(
        "contract_adjusted_eta_days"
    )

    stockout_before_eta = erp_assessment.get(
        "stockout_before_eta",
        False,
    )

    reassessment_findings = []
    requires_human_confirmation = False

    # 변경된 입고 예정일이 있으면 재고 소진일과 다시 비교
    if (
        inventory_days is not None
        and contract_adjusted_eta_days is not None
    ):
        adjusted_stockout = (
            inventory_days
            < contract_adjusted_eta_days
        )

        if adjusted_stockout:
            reassessment_findings.append(
                "계약상 조정된 입고 예정일보다 "
                "재고 소진일이 빠릅니다."
            )

            # 최초 분석에서 발견하지 못한 위험만 추가 반영
            if not stockout_before_eta:
                score_after += 10

            stockout_before_eta = True

        else:
            reassessment_findings.append(
                "계약상 조정된 입고 예정일 전까지 "
                "현재 재고로 대응할 수 있습니다."
            )

    # 질문은 있지만 변경된 ERP 데이터가 없는 경우
    elif questions:
        reassessment_findings.append(
            "계약 분석 질문은 존재하지만 변경된 입고 일정이 "
            "ERP 데이터에 없어 담당자 확인이 필요합니다."
        )
        requires_human_confirmation = True

    has_alternative_supplier = erp_assessment.get(
        "has_alternative_supplier",
        True,
    )

    # Contract Agent의 질문을 바탕으로 대체 공급사 재확인
    if (
        questions
        and has_alternative_supplier is False
    ):
        reassessment_findings.append(
            "계약 분석 결과를 반영해 재확인했으나 "
            "등록된 대체 공급사가 없습니다."
        )

    score_after = min(score_after, 100)
    findings.extend(reassessment_findings)

    # Agent 재실행 시 동일한 분석 문장이 중복되지 않도록 제거
    findings = list(dict.fromkeys(findings))

    updated_assessment = {
        **erp_assessment,
        "erp_exposure_score": score_after,
        "stockout_before_eta": stockout_before_eta,
        "findings": findings,
    }

    return {
        "erp_assessment": updated_assessment,
        "erp_reassessment": {
            "score_before": score_before,
            "score_after": score_after,
            "checked_questions": questions,
            "findings": reassessment_findings,
            "requires_human_confirmation": (
                requires_human_confirmation
            ),
        },
        "erp_reassessment_done": True,
    }
