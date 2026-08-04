from app.multi_agent.graph.state import BriefingState


FORBIDDEN_EXPRESSIONS = [
    "반드시 발생한다",
    "확실히 발생한다",
    "100% 발생한다",
    "무조건 중단된다",
]


def add_warning(
    warnings: list[str],
    message: str,
) -> None:
    """같은 경고가 중복으로 추가되지 않게 한다."""

    if message not in warnings:
        warnings.append(message)


def validate_contract_findings(
    contract_findings: list[dict],
    warnings: list[str],
) -> str | None:
    """계약 근거에 필수 출처 정보가 있는지 검사한다."""

    error_owner = None

    for finding in contract_findings:
        if finding.get("contract_id") in (None, ""):
            add_warning(
                warnings,
                "계약 근거에 contract_id가 없습니다.",
            )
            error_owner = "contract"

        page = finding.get(
            "page",
            finding.get("page_number"),
        )
        if page is None:
            add_warning(
                warnings,
                "계약 근거에 페이지 정보가 없습니다.",
            )
            error_owner = "contract"

        evidence_text = finding.get(
            "evidence_text",
            finding.get("content"),
        )
        if not evidence_text:
            add_warning(
                warnings,
                "계약 근거에 인용 문장이 없습니다.",
            )
            error_owner = "contract"

    return error_owner


def validate_llm_citations(
    briefing: str,
    contract_findings: list[dict],
    warnings: list[str],
) -> str | None:
    """LLM 브리핑에 계약 ID와 페이지가 인용됐는지 검사한다."""

    error_owner = None
    normalized_briefing = briefing.casefold()

    for finding in contract_findings:
        contract_id = finding.get("contract_id")
        page = finding.get(
            "page",
            finding.get("page_number"),
        )

        if (
            contract_id not in (None, "")
            and str(contract_id) not in briefing
        ):
            add_warning(
                warnings,
                (
                    "LLM 브리핑에 계약 ID 인용이 없습니다: "
                    f"{contract_id}"
                ),
            )
            error_owner = "response"

        if page is not None:
            page_references = [
                f"p.{page}",
                f"{page}페이지",
                f"페이지 {page}",
                f"page {page}",
                f"page: {page}",
            ]

            if not any(
                reference.casefold() in normalized_briefing
                for reference in page_references
            ):
                add_warning(
                    warnings,
                    (
                        "LLM 브리핑에 계약서 페이지 "
                        f"인용이 없습니다: {page}"
                    ),
                )
                error_owner = "response"

    return error_owner


def format_number_variants(
    value: object,
) -> list[str]:
    """숫자를 브리핑에 나올 법한 표기 형태(정수/소수 1·2자리)로 변환한다."""

    try:
        decimal_value = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return []

    variants = {
        f"{decimal_value:g}",
        f"{decimal_value:.1f}",
        f"{decimal_value:.2f}",
    }
    return list(variants)


def validate_numeric_facts(
    briefing: str,
    state: BriefingState,
    warnings: list[str],
) -> str | None:
    """
    브리핑에 등장해야 할 핵심 수치가 실제 계산값과 일치하는지 검사한다.

    규칙 1~2("입력에 없는 수량을 만들지 마라")는 프롬프트 지시일 뿐 프로그램으로
    강제되지 않았다 — 이 함수가 LLM이 점수·재고일수를 잘못 옮겨 적거나 지어내는
    환각을 잡아낸다. 값이 존재할 때만 검사한다(조기종료 경량 브리핑 등 값 자체가
    없는 경우까지 막으면 안 되므로).
    """

    error_owner = None
    erp_assessment = (
        state.get("erp_assessment", {}) or {}
    )
    contract_assessment = (
        state.get("contract_assessment", {}) or {}
    )

    facts = {
        "외부신호 점수": state.get(
            "external_signal_score"
        ),
        "ERP노출 점수": erp_assessment.get(
            "erp_exposure_score"
        ),
        "계약공백 점수": contract_assessment.get(
            "contract_gap_score"
        ),
        "최종 구매 위험 점수": state.get(
            "procurement_risk_score"
        ),
        "재고 일수": erp_assessment.get(
            "inventory_days"
        ),
        "안전재고 일수": erp_assessment.get(
            "safety_stock_days"
        ),
    }

    for label, value in facts.items():
        if value is None:
            continue

        variants = format_number_variants(value)
        if not variants:
            continue

        if not any(
            variant in briefing
            for variant in variants
        ):
            add_warning(
                warnings,
                (
                    f"LLM 브리핑에 {label}({value})과 "
                    "일치하는 숫자가 없습니다."
                ),
            )
            error_owner = "response"

    return error_owner


def validate_briefing_node(
    state: BriefingState,
) -> dict:
    """최종 브리핑의 필수 근거와 금지 표현을 검사한다."""

    briefing = state.get(
        "briefing",
        "",
    )
    risk_level = state.get(
        "procurement_risk_level",
        state.get("severity_tier", "normal"),
    )
    erp_assessment = state.get(
        "erp_assessment",
        {},
    )
    erp_findings = erp_assessment.get(
        "findings",
        [],
    )
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

    # 앞선 노드가 남긴 경고를 이어받는다. 빈 리스트로 시작하면 reviewer가 실행될 때마다
    # 그 경고들이 사라져, KG 게이트 우회처럼 **결과와 함께 남아야 할 사실**이 DB까지
    # 도달하지 못한다(응답 스키마에 warnings 말고는 그 사실을 실어 보낼 필드가 없다).
    # add_warning이 중복을 막으므로 reviewer가 여러 번 돌아도 같은 문구가 쌓이지 않는다.
    warnings = list(state.get("warnings", []))

    # 이어받은 경고는 reviewer의 판정 대상이 아니다. KG 게이트 우회·브리핑 LLM 공급자 같은
    # **환경 설정 고지**가 대부분이고, 그건 브리핑을 다시 써도 사라지지 않는다. 그런데도
    # review_passed를 전체 경고 개수로 매기면 (1) 검증 통과 브리핑이 구조적으로 0이 되고
    # (2) 고칠 수 없는 경고 때문에 retry_count가 올라 재실행이 헛돈다 — 실제로
    # KG_GATE_ENABLED=false로 내린 2026-08-03부터 review_passed가 전부 false로 뒤집혔다.
    # 그래서 이 노드가 **새로 찾은** 문제만 실패로 친다.
    inherited_warnings = set(warnings)
    error_owner = None

    if not briefing.strip():
        add_warning(
            warnings,
            "브리핑 내용이 없습니다.",
        )
        error_owner = "response"

    if risk_level in ("warning", "critical"):
        if not erp_findings:
            add_warning(
                warnings,
                (
                    "주의·심각 사건이지만 "
                    "ERP 분석 근거가 없습니다."
                ),
            )
            if error_owner is None:
                error_owner = "erp"

        if not contract_findings:
            add_warning(
                warnings,
                (
                    "주의·심각 사건이지만 "
                    "계약서 근거가 없습니다."
                ),
            )
            if error_owner is None:
                error_owner = "contract"

    contract_error = validate_contract_findings(
        contract_findings,
        warnings,
    )
    if contract_error is not None:
        error_owner = contract_error

    if (
        state.get("procurement_risk_level") is not None
        and not recommended_actions
    ):
        add_warning(
            warnings,
            "구매팀 권고 조치가 없습니다.",
        )
        error_owner = "response"

    if llm_used:
        risk_labels = {
            "normal": ["normal", "정상"],
            "warning": ["warning", "주의"],
            "critical": ["critical", "심각"],
        }
        expected_labels = risk_labels.get(
            risk_level,
            [],
        )

        if expected_labels and not any(
            label in briefing.casefold()
            for label in expected_labels
        ):
            add_warning(
                warnings,
                (
                    "LLM 브리핑에 최종 위험 단계가 "
                    f"없습니다: {risk_level}"
                ),
            )
            error_owner = "response"

        citation_error = validate_llm_citations(
            briefing,
            contract_findings,
            warnings,
        )
        if citation_error is not None:
            error_owner = citation_error

        numeric_error = validate_numeric_facts(
            briefing,
            state,
            warnings,
        )
        if numeric_error is not None:
            error_owner = numeric_error

    for expression in FORBIDDEN_EXPRESSIONS:
        if expression in briefing:
            add_warning(
                warnings,
                (
                    "확정적으로 단정하는 금지 표현이 "
                    f"포함되어 있습니다: {expression}"
                ),
            )
            error_owner = "response"

    # add_warning이 중복을 걸러내므로 개수 비교로는 부족하다(이어받은 문구와 같은 경고를
    # 이 노드가 다시 올리면 개수가 그대로다). 집합 차로 새 경고만 뽑는다.
    new_warnings = [
        warning
        for warning in warnings
        if warning not in inherited_warnings
    ]
    review_passed = not new_warnings
    retry_count = state.get(
        "retry_count",
        0,
    )

    if not review_passed:
        retry_count += 1

    return {
        "review_passed": review_passed,
        "validation_passed": review_passed,
        "error_owner": error_owner,
        "retry_count": retry_count,
        "warnings": warnings,
    }