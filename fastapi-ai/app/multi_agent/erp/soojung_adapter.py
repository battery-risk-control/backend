from typing import Any

from app.adapters.erp_agent_adapter import (
    adaptErpExposureRequest,
)
from app.agents.erp_exposure_agent import (
    runErpExposureAgent,
)
from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpExposureRequest,
    ErpExposureResponse,
)


def build_erp_findings(
    response: ErpExposureResponse,
) -> list[str]:
    """수정님 ERP 계산 근거를 브리핑용 문장으로 변환한다."""

    findings = []

    for evidence in response.calculationEvidence:
        findings.append(
            f"{evidence.metric}: {evidence.value} "
            f"(계산식: {evidence.formula})"
        )

    findings.extend(response.warnings)

    if not findings:
        score = response.erpExposureScore

        findings.append(
            f"ERP 노출도 점수는 {score}점이며 "
            f"등급은 {response.exposureLevel.value}입니다."
        )

    return findings


def resolve_alternative_supplier(
    request: ErpExposureRequest,
) -> bool | None:
    """
    대체 공급사 상태를 멀티에이전트의 Boolean 상태로 변환한다.

    APPROVED: 실제 사용 가능한 공급사 존재
    NONE: 사용 가능한 공급사 없음
    CONDITIONAL/PENDING: 아직 확정할 수 없음
    """

    adapted_request = adaptErpExposureRequest(
        request,
    )

    if adapted_request.materialContext is None:
        return None

    status = (
        adapted_request.materialContext
        .alternativeSupplierStatus
    )

    if status is AlternativeSupplierStatus.APPROVED:
        return True

    if status is AlternativeSupplierStatus.NONE:
        return False

    return None


def adapt_erp_exposure_response(
    request: ErpExposureRequest,
    response: ErpExposureResponse,
) -> dict[str, Any]:
    """
    수정님 ERP 응답을 우리 BriefingState 필드로 변환한다.
    """

    expected_gap = (
        response.facts.expectedSupplyGapDays
    )
    projected_gap = (
        response.facts.projectedSupplyGapDays
    )

    stockout_before_eta = (
        (expected_gap is not None and expected_gap > 0)
        or (
            projected_gap is not None
            and projected_gap > 0
        )
    )

    questions = [
        question.question
        for question
        in response.questionsForContractAgent
    ]

    erp_assessment = {
        "erp_exposure_score": (
            response.erpExposureScore or 0
        ),
        "exposure_level": (
            response.exposureLevel.value.lower()
        ),
        "stockout_before_eta": (
            stockout_before_eta
        ),
        "has_alternative_supplier": (
            resolve_alternative_supplier(request)
        ),
        "inventory_days": (
            response.facts.inventoryDays
        ),
        "safety_stock_days": (
            response.facts.safetyStockDays
        ),
        "next_inbound_eta_days": (
            response.facts.nextEtaDays
        ),
        "expected_supply_gap_days": expected_gap,
        "projected_supply_gap_days": projected_gap,
        "data_quality_status": (
            response.dataQualityStatus.value
        ),
        "manual_review_required": (
            response.manualReviewRequired
        ),
        "forced_critical": (
            response.forcedCritical
        ),
        "risk_components": (
            response.riskComponents.model_dump(
                mode="json",
            )
        ),
        "calculation_evidence": [
            evidence.model_dump(mode="json")
            for evidence
            in response.calculationEvidence
        ],
        "findings": build_erp_findings(response),
    }

    return {
        "erp_assessment": erp_assessment,
        "erp_findings": erp_assessment,
        "questions_for_contract_agent": questions,
        "affected_contract_ids": list(
            response.affectedContractIds,
        ),
        "erp_exposure_response": (
            response.model_dump(mode="json")
        ),
    }


def analyze_soojung_erp_node(
    state: dict[str, Any],
) -> dict[str, Any]:
    """
    우리 멀티에이전트에서 수정님 ERP Agent를 실행한다.

    erp_context에는 수정님의 ErpExposureRequest JSON이 들어온다.
    """

    raw_erp_context = state.get(
        "erp_context",
    )

    if not raw_erp_context:
        return {
            "erp_assessment": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": [
                    "ERP 요청 데이터가 없습니다.",
                ],
            },
            "erp_findings": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": [
                    "ERP 요청 데이터가 없습니다.",
                ],
            },
            "questions_for_contract_agent": [],
            "affected_contract_ids": [],
        }

    request = ErpExposureRequest.model_validate(
        raw_erp_context,
    )

    agent_result = runErpExposureAgent(
        {
            "eventId": request.eventId,
            "erpRequest": request,
        }
    )

    response = agent_result.get(
        "erpExposure",
    )

    if response is None:
        errors = agent_result.get(
            "errors",
            [],
        )

        return {
            "erp_assessment": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": (
                    errors
                    or ["ERP 분석 결과가 없습니다."]
                ),
            },
            "erp_findings": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": (
                    errors
                    or ["ERP 분석 결과가 없습니다."]
                ),
            },
            "questions_for_contract_agent": [],
            "affected_contract_ids": [],
        }

    return adapt_erp_exposure_response(
        request=request,
        response=response,
    )

def recheck_soojung_erp_node(
    state: dict[str, Any],
) -> dict[str, Any]:
    """
    Contract Agent 검색 결과를 반영해
    수정님 ERP 분석 결과를 재검토한다.

    계약 검색만으로 ERP 원천 수치나 점수를 임의로 변경하지 않고,
    실제 납기 변경 반영 여부는 담당자 확인 대상으로 남긴다.
    """

    erp_assessment = dict(
        state.get(
            "erp_assessment",
            {},
        )
    )
    score_before = erp_assessment.get(
        "erp_exposure_score",
        0,
    )

    questions = list(
        state.get(
            "questions_for_erp_agent",
            [],
        )
    )
    contract_findings = list(
        state.get(
            "contract_findings",
            [],
        )
    )

    findings = []

    if contract_findings:
        findings.append(
            "계약 검색 근거를 수신했습니다. "
            "계약 조건이 실제 발주 납기와 ERP 계획에 "
            "반영됐는지는 담당자 확인이 필요합니다."
        )
    else:
        findings.append(
            "관련 계약 근거를 검색 결과에서 확인하지 못했습니다. "
            "ERP 재고 계획과 계약서를 추가로 확인해야 합니다."
        )

    existing_findings = list(
        erp_assessment.get(
            "findings",
            [],
        )
    )

    for finding in findings:
        if finding not in existing_findings:
            existing_findings.append(finding)

    erp_assessment["findings"] = existing_findings

    return {
        "erp_assessment": erp_assessment,
        "erp_findings": erp_assessment,
        "erp_reassessment": {
            "score_before": score_before,
            "score_after": score_before,
            "checked_questions": questions,
            "contract_evidence_count": len(
                contract_findings,
            ),
            "findings": findings,
            "requires_human_confirmation": True,
        },
        "erp_reassessment_done": True,
    }