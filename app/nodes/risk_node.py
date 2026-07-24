from app.graph.state import BriefingState, RiskLevel


# PoC 단계의 초기 가중치
EXTERNAL_SIGNAL_WEIGHT = 0.35
ERP_EXPOSURE_WEIGHT = 0.45
CONTRACT_GAP_WEIGHT = 0.20

def clamp_score(score: float) -> int:
    """점수를 0~100 범위의 정수로 제한한다."""
    return round(max(0,min(100,score)))

def score_to_level(score: int) -> RiskLevel:
    """최종 점수를 위험 단계로 변환한다."""
    if score >= 70:
        return "critical"

    if score >= 40:
        return "warning"

    return "normal"

def calculate_procurement_risk_node(
        state: BriefingState,

) -> dict:
    """외부 신호, ERP 노출도, 계약 위험을 결합한다."""

    external_score = state.get("external_signal_score", 0)

    erp_assessment = state.get("erp_assessment", {})
    erp_score = erp_assessment.get("erp_exposure_score", 0)

    contract_assessment = state.get("contract_assessment", {})
    contract_score = contract_assessment.get("contract_gap_score", 0)

    weighted_score = (
        external_score * EXTERNAL_SIGNAL_WEIGHT
        + erp_score * ERP_EXPOSURE_WEIGHT
        + contract_score * CONTRACT_GAP_WEIGHT
    )

    final_score = clamp_score(weighted_score)
    final_level = score_to_level(final_score)

    risk_reasons = [
        f"외부 공급망 신호 점수: {external_score}",
        f"ERP 내부 노출도 점수: {erp_score}",
        f"계약 보호 공백 점수: {contract_score}",
    ]

    if erp_assessment.get("stockout_before_eta") is True:
        final_score = max(final_score, 70)
        final_level = "critical"
        risk_reasons.append(
            "예상 입고일 전에 현재 재고가 소진될 가능성이 있습니다."
        )

    return {
        "procurement_risk_score": final_score,
        "procurement_risk_level": final_level,
        "risk_reasons": risk_reasons,
    }
