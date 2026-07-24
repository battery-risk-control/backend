from app.graph.state import BriefingState


FORBIDDEN_EXPRESSIONS = [
    "반드시 발생한다",
    "확실히 발생한다",
    "100% 발생한다",
    "무조건 중단된다",
]


def validate_briefing_node(
    state: BriefingState,
) -> dict:
    """
    최종 브리핑의 근거 누락과 금지 표현을 검사한다.

    규칙 기반 Reviewer이며, 향후 LLM Reviewer를 추가해도
    기본적인 필드와 근거 검사는 이 함수에서 수행한다.
    """
    briefing = state.get(
        "briefing",
        "",
    )

    risk_level = state.get(
        "procurement_risk_level",
        state.get("severity_tier", "normal"),
    )

    # =========================================================
    # ERP 분석 결과
    # =========================================================

    erp_assessment = state.get(
        "erp_assessment"
    )

    if erp_assessment:
        erp_findings = erp_assessment.get(
            "findings",
            [],
        )
    else:
        # 기존 코드와 테스트 호환용
        erp_findings = state.get(
            "erp_findings",
            {},
        ).get("findings", [])

    # =========================================================
    # Contract 및 Response 결과
    # =========================================================

    contract_findings = state.get(
        "contract_findings",
        [],
    )

    recommended_actions = state.get(
        "recommended_actions",
        [],
    )

    llm_used = state.get(
        "llm_used",
        False,
    )

    warnings = []
    error_owner = None

    # =========================================================
    # 1. 브리핑 내용 검사
    # =========================================================

    if not briefing.strip():
        warnings.append(
            "브리핑 내용이 없습니다."
        )
        error_owner = "response"

    # =========================================================
    # 2. 위험 사건의 ERP 및 계약 근거 검사
    # =========================================================

    if risk_level in ["warning", "critical"]:
        if not erp_findings:
            warnings.append(
                "주의·심각 사건이지만 "
                "ERP 분석 근거가 없습니다."
            )

            if error_owner is None:
                error_owner = "erp"

        if not contract_findings:
            warnings.append(
                "주의·심각 사건이지만 "
                "계약서 근거가 없습니다."
            )

            if error_owner is None:
                error_owner = "contract"

    # =========================================================
    # 3. Contract Agent 결과의 필수 근거 검사
    # =========================================================

    if (
        state.get("procurement_risk_level")
        is not None
    ):
        for finding in contract_findings:
            if not finding.get("contract_id"):
                warnings.append(
                    "계약 근거에 contract_id가 없습니다."
                )
                error_owner = "contract"

            if finding.get("page") is None:
                warnings.append(
                    "계약 근거에 페이지 정보가 없습니다."
                )
                error_owner = "contract"

            if not finding.get("evidence_text"):
                warnings.append(
                    "계약 근거에 인용 문장이 없습니다."
                )
                error_owner = "contract"

    # =========================================================
    # 4. 구매팀 권고 조치 검사
    # =========================================================

    if (
        state.get("procurement_risk_level")
        is not None
        and not recommended_actions
    ):
        warnings.append(
            "구매팀 권고 조치가 없습니다."
        )
        error_owner = "response"

    # =========================================================
    # 5. LLM 브리핑 검사
    # =========================================================

    if llm_used:
        # 영문 페이지 표현의 대소문자를 무시하기 위해 사용
        briefing_normalized = briefing.casefold()

        # -----------------------------------------------------
        # 5-1. 표준 위험 단계 명칭 검사
        # -----------------------------------------------------

        risk_level_labels = {
            "normal": "정상",
            "warning": "주의",
            "critical": "심각",
        }

        expected_label = risk_level_labels.get(
            risk_level
        )

        if (
            expected_label is not None
            and expected_label not in briefing
        ):
            warnings.append(
                "LLM 브리핑에 표준 위험 단계 명칭이 "
                f"없습니다: {expected_label}"
            )
            error_owner = "response"

        # -----------------------------------------------------
        # 5-2. 계약서 ID 및 페이지 인용 검사
        # -----------------------------------------------------

        for finding in contract_findings:
            contract_id = finding.get(
                "contract_id"
            )
            page = finding.get("page")

            if (
                contract_id is not None
                and str(contract_id) not in briefing
            ):
                warnings.append(
                    "LLM 브리핑에 계약서 ID 인용이 "
                    f"없습니다: {contract_id}"
                )
                error_owner = "response"

            if page is not None:
                page_references = [
                    f"p.{page}",
                    f"{page}페이지",
                    f"페이지 {page}",
                    f"page {page}",
                ]

                if not any(
                    reference.casefold()
                    in briefing_normalized
                    for reference in page_references
                ):
                    warnings.append(
                        "LLM 브리핑에 계약서 페이지 인용이 "
                        f"없습니다: {page}"
                    )
                    error_owner = "response"

        # -----------------------------------------------------
        # 5-3. ERP 사실의 긍정·부정 반전 검사
        # -----------------------------------------------------

        stockout_before_eta = False
        has_alternative_supplier = None

        if erp_assessment:
            stockout_before_eta = (
                erp_assessment.get(
                    "stockout_before_eta",
                    False,
                )
            )

            has_alternative_supplier = (
                erp_assessment.get(
                    "has_alternative_supplier"
                )
            )

        if stockout_before_eta is True:
            contradictory_stockout_phrases = [
                "재고 소진 전에 입고가 가능",
                "재고 소진 전까지 입고가 가능",
                "재고가 소진되기 전에 입고가 가능",
            ]

            for phrase in contradictory_stockout_phrases:
                if phrase in briefing:
                    warnings.append(
                        "LLM 브리핑이 ERP 재고 소진 "
                        f"결과와 모순됩니다: {phrase}"
                    )
                    error_owner = "response"
                    break

        if has_alternative_supplier is False:
            uncertain_alternative_phrases = [
                "대체 공급사가 등록되어 있는지",
                "대체 공급사 존재 여부를 확인",
                "등록된 대체 공급사가 있는지",
            ]

            for phrase in uncertain_alternative_phrases:
                if phrase in briefing:
                    warnings.append(
                        "ERP에서 대체 공급사 부재가 "
                        "확인됐지만 LLM 브리핑이 이를 "
                        f"미확인 상태로 표현했습니다: {phrase}"
                    )
                    error_owner = "response"
                    break

        # -----------------------------------------------------
        # 5-4. 검색 결과만으로 계약 조항 부재 단정 검사
        # -----------------------------------------------------

        unsupported_contract_phrases = [
            "조항이 존재하지 않",
            "계약 조항이 없",
        ]

        for phrase in unsupported_contract_phrases:
            if phrase in briefing:
                warnings.append(
                    "검색 결과만으로 계약 조항의 부재를 "
                    f"단정했습니다: {phrase}"
                )
                error_owner = "response"
                break

        # -----------------------------------------------------
        # 5-5. 내부 프롬프트 노출 검사
        # -----------------------------------------------------

        prompt_leakage_phrases = [
            "지시하지 마세요",
            "작성하지 마세요",
        ]

        for phrase in prompt_leakage_phrases:
            if phrase in briefing:
                warnings.append(
                    "LLM이 내부 작성 규칙을 사용자용 "
                    f"브리핑에 노출했습니다: {phrase}"
                )
                error_owner = "response"
                break

    # =========================================================
    # 6. 과도하게 확정적인 표현 검사
    # =========================================================

    for expression in FORBIDDEN_EXPRESSIONS:
        if expression in briefing:
            warnings.append(
                "확정적으로 단정하는 금지 표현이 "
                f"포함되어 있습니다: {expression}"
            )
            error_owner = "response"

    # =========================================================
    # 7. 최종 검증 결과
    # =========================================================

    review_passed = len(warnings) == 0

    retry_count = state.get(
        "retry_count",
        0,
    )

    if not review_passed:
        retry_count += 1

    return {
        # Supervisor가 사용하는 필드
        "review_passed": review_passed,
        "error_owner": error_owner,
        "retry_count": retry_count,
        "warnings": warnings,

        # 기존 코드와 테스트 호환용
        "validation_passed": review_passed,
    }
