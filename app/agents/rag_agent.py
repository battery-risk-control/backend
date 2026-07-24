from app.graph.state import BriefingState


# Impact Domain별 Mock 계약 조항
# 추후 ChromaDB 검색 결과로 교체할 예정
CLAUSE_BY_DOMAIN = {
    "production": {
        "clause_type": "force_majeure",
        "clause_name_kr": "불가항력 조항",
        "evidence_text": (
            "천재지변 또는 생산시설 중단으로 공급이 불가능한 경우 "
            "공급자는 즉시 통보해야 한다."
        ),
    },
    "logistics": {
        "clause_type": "delivery_delay",
        "clause_name_kr": "납기 지연 조항",
        "evidence_text": (
            "약정 납기일을 초과한 경우 공급자는 지연 사유와 "
            "변경 일정을 통보해야 한다."
        ),
    },
    "policy": {
        "clause_type": "regulatory_compliance",
        "clause_name_kr": "규제 준수 조항",
        "evidence_text": (
            "공급자는 관련 국가의 수출입 규제와 "
            "원산지 요건을 준수해야 한다."
        ),
    },
    "market": {
        "clause_type": "price_adjustment",
        "clause_name_kr": "가격 조정 조항",
        "evidence_text": (
            "기준 원자재 가격이 일정 범위를 초과하여 변동할 경우 "
            "계약 단가를 재협의할 수 있다."
        ),
    },
    "geopolitical": {
        "clause_type": "force_majeure",
        "clause_name_kr": "불가항력 조항",
        "evidence_text": (
            "전쟁, 내전 또는 정부 조치로 계약 이행이 불가능한 경우 "
            "불가항력으로 처리할 수 있다."
        ),
    },
}


def search_contracts_node(state: BriefingState) -> dict:
    """
    ERP Agent가 지정한 계약서와 질문을 바탕으로 관련 조항을 검색한다.

    현재는 ChromaDB 연결 전 Mock 구현이다.
    FastAPI가 업무 DB에 직접 접근하지 않으며, 실제 계약 문서는
    추후 벡터 DB 검색 도구를 통해 조회한다.
    """
    impact_domain = state.get(
        "impact_domain_final",
        "unknown",
    )
    affected_materials = state.get(
        "affected_materials",
        [],
    )
    affected_contract_ids = state.get(
        "affected_contract_ids",
        [],
    )
    questions_received = state.get(
        "questions_for_contract_agent",
        [],
    )

    clause = CLAUSE_BY_DOMAIN.get(impact_domain)

    # 관련 조항을 찾지 못한 경우 계약 보호 공백을 높게 판단
    if clause is None:
        return {
            "contract_assessment": {
                "contract_gap_score": 80,
                "protection_status": "not_found",
                "questions_received": questions_received,
            },
            "contract_findings": [],
            "questions_for_erp_agent": [
                "관련 계약 조항을 찾지 못했습니다. "
                "대체 공급사와 현재 재고 계획을 확인해야 합니다."
            ],
        }

    # ERP Agent가 지정한 계약서가 있으면 해당 ID를 사용
    contract_id = (
        affected_contract_ids[0]
        if affected_contract_ids
        else "MOCK-CONTRACT-001"
    )

    contract_findings = [
        {
            "contract_id": contract_id,
            "material": (
                affected_materials[0]
                if affected_materials
                else "unknown"
            ),
            "clause_type": clause["clause_type"],
            "clause_name_kr": clause["clause_name_kr"],
            "evidence_text": clause["evidence_text"],
            "page": 1,
            "source_type": "mock",
        }
    ]

    questions_for_erp_agent = []

    if clause["clause_type"] == "delivery_delay":
        questions_for_erp_agent.append(
            "계약상 납기 변경 일정을 반영했을 때 "
            "재고 소진 전에 입고가 가능한가?"
        )

    if clause["clause_type"] == "force_majeure":
        questions_for_erp_agent.append(
            "불가항력 기간에 사용할 수 있는 "
            "대체 공급사 또는 재고가 있는가?"
        )

    return {
        "contract_assessment": {
            # 관련 조항은 있지만 실제 보상과 대응 가능성은
            # 추가 검토가 필요하므로 Mock 점수 30 사용
            "contract_gap_score": 30,
            "protection_status": "partial",
            "questions_received": questions_received,
        },
        "contract_findings": contract_findings,
        "questions_for_erp_agent": questions_for_erp_agent,
    }
