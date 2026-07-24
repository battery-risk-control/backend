from app.nodes.verification_node import validate_briefing_node


def test_verification_passes_with_required_evidence():
    state = {
        "severity_tier": "warning",
        "briefing": "재고와 계약 근거를 확인한 내부 참고 브리핑입니다.",

        "erp_findings": {
            "findings": [
                "현재 재고가 안전재고 기준보다 2일 부족합니다."
            ]
        },

        "contract_findings": [
            {
                "contract_id": "MOCK-CONTRACT-001",
                "clause_name_kr": "납기 지연 조항",
                "evidence_text": "공급자는 지연 사유를 통보해야 한다."
            }
        ]
    }

    result = validate_briefing_node(state)

    assert result["validation_passed"] is True
    assert result["warnings"] == []


def test_verification_fails_without_evidence():
    state = {
        "severity_tier": "critical",
        "briefing": "공급 중단 가능성을 확인해야 합니다.",
        "erp_findings": {
            "findings": []
        },
        "contract_findings": []
    }

    result = validate_briefing_node(state)

    assert result["validation_passed"] is False
    assert "주의·심각 사건이지만 ERP 분석 근거가 없습니다." in result["warnings"]
    assert "주의·심각 사건이지만 계약서 근거가 없습니다." in result["warnings"]


def test_verification_rejects_forbidden_expression():
    state = {
        "severity_tier": "normal",
        "briefing": "이 사건으로 공급 중단이 반드시 발생한다.",
        "erp_findings": {
            "findings": []
        },
        "contract_findings": []
    }

    result = validate_briefing_node(state)

    assert result["validation_passed"] is False
    assert any(
        "금지 표현" in warning
        for warning in result["warnings"]
    )


def test_reviewer_assigns_contract_error_owner():
    state = {
        "procurement_risk_level": "critical",
        "procurement_risk_score": 85,
        "briefing": "니켈 공급 위험에 대한 구매팀 브리핑입니다.",
        "erp_assessment": {
            "findings": [
                "입고 전에 재고가 소진될 가능성이 있습니다."
            ]
        },
        "contract_findings": [
            {
                "contract_id": "CONTRACT-001",
                # page가 의도적으로 빠져 있음
                "evidence_text": "납기 지연 시 공급자는 통보해야 한다.",
            }
        ],
        "recommended_actions": [
            "공급사 납기 일정을 확인합니다."
        ],
        "retry_count": 0,
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["validation_passed"] is False
    assert result["error_owner"] == "contract"
    assert result["retry_count"] == 1

    assert any(
        "페이지 정보가 없습니다" in warning
        for warning in result["warnings"]
    )

def test_reviewer_rejects_llm_briefing_without_citation():
    state = {
        "llm_used": True,
        "procurement_risk_level": "critical",

        "briefing": (
            "현재 구매 위험 단계는 심각입니다. "
            "납기 지연 조항이 확인되었습니다."
        ),

        "erp_assessment": {
            "findings": [
                "입고 전에 재고가 소진될 수 있습니다."
            ]
        },

        "contract_findings": [
            {
                "contract_id": "CTR-010",
                "page": 3,
                "evidence_text": (
                    "공급자는 변경된 납기 일정을 "
                    "통보해야 한다."
                ),
            }
        ],

        "recommended_actions": [
            "공급사에 변경된 납기를 확인합니다."
        ],

        "retry_count": 0,
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"
    assert result["retry_count"] == 1

    assert any(
        "계약서 ID 인용이 없습니다"
        in warning
        for warning in result["warnings"]
    )

    assert any(
        "계약서 페이지 인용이 없습니다"
        in warning
        for warning in result["warnings"]
    )

def test_reviewer_accepts_grounded_llm_briefing():
    state = {
        "llm_used": True,
        "procurement_risk_level": "critical",

        "briefing": (
            "현재 구매 위험 단계는 심각입니다. "
            "계약서 [CTR-010 p.3]에서는 공급자가 "
            "변경된 납기 일정을 통보해야 한다고 "
            "규정하고 있습니다."
        ),

        "erp_assessment": {
            "findings": [
                "입고 전에 재고가 소진될 수 있습니다."
            ]
        },

        "contract_findings": [
            {
                "contract_id": "CTR-010",
                "page": 3,
                "evidence_text": (
                    "공급자는 변경된 납기 일정을 "
                    "통보해야 한다."
                ),
            }
        ],

        "recommended_actions": [
            "공급사에 변경된 납기 일정을 확인합니다."
        ],

        "retry_count": 0,
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is True
    assert result["error_owner"] is None
    assert result["retry_count"] == 0
    assert result["warnings"] == []

def test_reviewer_rejects_erp_fact_reversal():
    state = {
        "llm_used": True,
        "procurement_risk_level": "critical",

        "briefing": (
            "현재 위험 단계는 심각입니다. "
            "계약서 [CTR-010 p.1]에 납기 지연 조항이 있습니다. "
            "현재 재고 소진 전에 입고가 가능하며, "
            "대체 공급사가 등록되어 있는지 확인이 필요합니다."
        ),

        "erp_assessment": {
            "stockout_before_eta": True,
            "has_alternative_supplier": False,
            "findings": [
                "입고 전에 재고가 소진될 수 있습니다.",
                "등록된 대체 공급사가 없습니다.",
            ],
        },

        "contract_findings": [
            {
                "contract_id": "CTR-010",
                "page": 1,
                "evidence_text": (
                    "공급자는 변경된 납기 일정을 "
                    "통보해야 한다."
                ),
            }
        ],

        "recommended_actions": [
            "공급사의 변경된 납기를 확인합니다."
        ],

        "retry_count": 0,
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"

    assert any(
        "ERP 재고 소진 결과와 모순됩니다"
        in warning
        for warning in result["warnings"]
    )

    assert any(
        "대체 공급사 부재가 확인됐지만"
        in warning
        for warning in result["warnings"]
    )

def test_reviewer_detects_contract_absence_claim_and_prompt_leakage():
    state = {
        "llm_used": True,
        "procurement_risk_level": "critical",

        "briefing": (
            "현재 위험 단계는 심각입니다. "
            "계약서 [CTR-010 Page 1]에서 "
            "납기 지연 통보 의무를 확인했습니다. "
            "공급 중단 관련 조항이 존재하지 않습니다. "
            "즉시 추가 발주를 지시하지 마세요."
        ),

        "erp_assessment": {
            "stockout_before_eta": True,
            "has_alternative_supplier": False,
            "findings": [
                "입고 전에 재고가 소진될 수 있습니다.",
                "등록된 대체 공급사가 없습니다.",
            ],
        },

        "contract_findings": [
            {
                "contract_id": "CTR-010",
                "page": 1,
                "evidence_text": (
                    "공급자는 변경된 납기 일정을 "
                    "통보해야 한다."
                ),
            }
        ],

        "recommended_actions": [
            "기존 발주의 납기와 수량을 확인합니다."
        ],

        "retry_count": 0,
    }

    result = validate_briefing_node(state)

    assert result["review_passed"] is False
    assert result["error_owner"] == "response"

    # Page 1은 정상적인 페이지 인용으로 인정
    assert not any(
        "페이지 인용이 없습니다"
        in warning
        for warning in result["warnings"]
    )

    # 검색 결과만으로 계약 조항 부재를 단정하면 실패
    assert any(
        "계약 조항의 부재를 단정했습니다"
        in warning
        for warning in result["warnings"]
    )

    # 내부 프롬프트 문장을 브리핑에 노출하면 실패
    assert any(
        "내부 작성 규칙을 사용자용 브리핑에 노출"
        in warning
        for warning in result["warnings"]
    )
