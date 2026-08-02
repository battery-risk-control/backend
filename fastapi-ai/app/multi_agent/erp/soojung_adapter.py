from typing import Any

from pydantic import ValidationError

from app.adapters.erp_agent_adapter import (
    adaptErpExposureRequest,
)
from app.agents.erp_exposure_agent import (
    runErpExposureAgent,
)
from app.multi_agent.graph.routing import (
    MAX_NEGOTIATION_ROUNDS,
)
from app.schemas.erp import (
    AlternativeSupplierStatus,
    ErpExposureRequest,
    ErpExposureResponse,
    ErpRiskComponents,
)
from app.services.erp_calculator import (
    calculateErpExposureScore,
)
from app.services.erp_rule_loader import (
    loadErpRules,
)


# 재검토 라운드에서 점수 변화가 이보다 작으면 더 물어봐도 의미 없다고 보고 수렴시킨다.
SCORE_CONVERGENCE_THRESHOLD = 5

# 보호 근거를 못 찾은 걸로 치는 protection_status
UNPROTECTED_STATUSES = ("unprotected", "not_found")

# 계속 협상할 가치가 있다고 보는 노출등급 — 정상(normal)이면 더 물을 이유 없음
STILL_RISKY_EXPOSURE_LEVELS = ("critical", "warning")


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
        "supplier_assessments": [
            assessment.model_dump(
                mode="json",
            )
            for assessment
            in response.supplierAssessments
        ],
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

    try:
        request = ErpExposureRequest.model_validate(
            raw_erp_context,
        )
    except ValidationError as error:
        return {
            "erp_assessment": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": [
                    f"ERP_REQUEST_INVALID: {error}",
                ],
            },
            "erp_findings": {
                "erp_exposure_score": 0,
                "manual_review_required": True,
                "findings": [
                    f"ERP_REQUEST_INVALID: {error}",
                ],
            },
            "questions_for_contract_agent": [],
            "affected_contract_ids": [],
        }

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
        if agent_result.get("erpAgentStatus") == "NOT_REQUIRED":
            return {
                "erp_assessment": {
                    "erp_exposure_score": 0,
                    "manual_review_required": False,
                    "findings": [
                        "erpAnalysisRequired=false로 "
                        "ERP 분석이 필요하지 않습니다.",
                    ],
                },
                "erp_findings": {
                    "erp_exposure_score": 0,
                    "manual_review_required": False,
                    "findings": [
                        "erpAnalysisRequired=false로 "
                        "ERP 분석이 필요하지 않습니다.",
                    ],
                },
                "questions_for_contract_agent": [],
                "affected_contract_ids": [],
            }

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
    Contract Agent 검색 결과를 반영해 ERP 분석 결과를 재검토한다.

    이전에는 계약 검색 결과와 무관하게 score_after를 score_before와 항상 같게
    둬서 "재검토"가 이름만 있고 실제로는 아무것도 안 바뀌었다. 이제는
    contract_gap_score를 ERP 리스크 공식의 6번째 구성요소(contractProtection,
    erp_rules.yaml)로 실제로 채워 넣고 calculateErpExposureScore로 다시 계산한다.

    최대 MAX_NEGOTIATION_ROUNDS 라운드까지 왕복 가능 — 아직 보호 근거를
    못 찾았는데 리스크 등급이 여전히 위험하면 Contract Agent에게 한 번 더
    확인을 요청하고(questions_for_contract_agent_round2), 점수가 수렴했거나
    라운드 상한에 닿으면 협상을 종료한다.
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

    negotiation_round = (
        state.get("negotiation_round", 0) + 1
    )

    questions = list(
        state.get(
            "questions_for_erp_agent",
            [],
        )
    )
    contract_assessment = dict(
        state.get(
            "contract_assessment",
            {},
        )
    )
    contract_findings = list(
        state.get(
            "contract_findings",
            [],
        )
    )
    contract_gap_score = contract_assessment.get(
        "contract_gap_score",
    )
    protection_status = contract_assessment.get(
        "protection_status",
    )

    findings = []
    risk_components_dict = dict(
        erp_assessment.get(
            "risk_components",
            {},
        )
    )

    if contract_gap_score is not None:
        risk_components_dict[
            "contractProtectionRiskScore"
        ] = contract_gap_score

        rules = loadErpRules()
        recalculated_score = calculateErpExposureScore(
            riskComponents=ErpRiskComponents.model_validate(
                risk_components_dict,
            ),
            rules=rules,
        )
        score_after = (
            recalculated_score
            if recalculated_score is not None
            else score_before
        )

        findings.append(
            f"{negotiation_round}차 재검토: 계약 근거 반영 전 "
            f"{score_before}점 → 반영 후 {score_after}점 "
            f"(계약 보호 공백 점수 {contract_gap_score}점 반영, "
            f"protection_status={protection_status})"
        )
    else:
        score_after = score_before
        findings.append(
            "관련 계약 근거를 검색 결과에서 확인하지 못했습니다. "
            "ERP 재고 계획과 계약서를 추가로 확인해야 합니다."
        )

    erp_assessment["erp_exposure_score"] = score_after
    erp_assessment["risk_components"] = risk_components_dict

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

    # 종료/계속 협상 판정.
    score_delta = abs(score_after - score_before)
    round_capped = (
        negotiation_round >= MAX_NEGOTIATION_ROUNDS
    )
    converged_by_score = (
        score_delta < SCORE_CONVERGENCE_THRESHOLD
    )
    exposure_level = erp_assessment.get(
        "exposure_level",
    )

    if round_capped or converged_by_score:
        questions_for_contract_agent_round2: list[str] = []
    else:
        still_uncertain = (
            protection_status in UNPROTECTED_STATUSES
            and exposure_level
            in STILL_RISKY_EXPOSURE_LEVELS
        )

        questions_for_contract_agent_round2 = (
            [
                "현재까지 발견된 계약 조항으로는 리스크가 "
                "해소되지 않습니다. 불가항력, 공급 물량 확약 등 "
                "다른 보호조항이 있는지 다시 확인해야 합니다."
            ]
            if still_uncertain
            else []
        )

    return {
        "erp_assessment": erp_assessment,
        "erp_findings": erp_assessment,
        "erp_reassessment": {
            "score_before": score_before,
            "score_after": score_after,
            "checked_questions": questions,
            "contract_evidence_count": len(
                contract_findings,
            ),
            "findings": findings,
            "requires_human_confirmation": (
                bool(questions_for_contract_agent_round2)
                or contract_gap_score is None
            ),
        },
        "negotiation_round": negotiation_round,
        # 이번 라운드 질문은 소비했으니 비워서 supervisor가 같은 질문으로
        # erp_recheck에 재진입하지 않게 한다.
        "questions_for_erp_agent": [],
        "questions_for_contract_agent_round2": (
            questions_for_contract_agent_round2
        ),
    }