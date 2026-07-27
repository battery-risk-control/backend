from typing import Literal, TypedDict


RiskLevel = Literal["normal", "warning", "critical"]


class BriefingState(TypedDict, total=False):
    # =========================================================
    # 1. 뉴스 분석 결과
    # =========================================================

    # 개별 뉴스 식별자
    news_id: str

    # 뉴스 원문과 LLM 요약
    title: str
    article_text: str
    summary_kr: str

    # LLM이 생성한 Impact Domain 초안
    impact_domain_draft: str

    # XGBoost가 분류한 최종 Impact Domain
    impact_domain_final: str

    # 뉴스에서 추출한 영향 원자재
    affected_materials: list[str]

    # 뉴스와 외부 데이터만 사용한 1차 위험 신호
    external_signal_level: RiskLevel
    external_signal_score: int

    # =========================================================
    # 2. ERP Exposure Agent
    # =========================================================

    # Spring Boot가 ERP DB에서 조회하여 전달하는 원본 데이터
    # FastAPI는 ERP DB에 직접 접근하지 않음
    erp_context: dict

    # ERP Agent가 계산한 회사 내부 노출도 분석 결과
    erp_assessment: dict

    # ERP Agent가 Contract Agent에게 전달할 질문
    questions_for_contract_agent: list[str]

    # ERP 분석으로 특정된 계약서 식별자
    affected_contract_ids: list[str]

    # =========================================================
    # 3. Contract RAG Agent
    # =========================================================

    # Minji RAG 서비스가 사용하는 PostgreSQL 내부 숫자 ID
    # ERP의 외부 문자열 ID(예: CTR-001)와 구분한다.
    rag_contract_id: int | None
    rag_supplier_id: int | None
    rag_material_id: int | None

    # Contract Agent가 분석한 계약상 보호와 위험 요소
    contract_assessment: dict

    # 검색된 계약 조항과 출처 근거
    contract_findings: list[dict]

    # Contract Agent가 ERP Agent에게 다시 확인할 질문
    questions_for_erp_agent: list[str]

    # Contract Agent의 질문을 반영한 ERP 재평가 결과
    erp_reassessment: dict

    # ERP 재평가가 이미 실행됐는지 표시
    # Contract → ERP 순환을 한 번으로 제한한다.
    erp_reassessment_done: bool

    # =========================================================
    # 4. 최종 구매 리스크
    # =========================================================

    # 외부 신호, ERP 노출도, 계약 위험을 결합한 최종 결과
    procurement_risk_level: RiskLevel | None
    procurement_risk_score: int

    # 규칙 엔진이 위험도를 결정한 근거
    risk_reasons: list[str]

    # =========================================================
    # 5. Response Agent
    # =========================================================
    # 요청별 LLM 사용 여부
    use_llm: bool

    # 실제 LLM 사용 여부와 fallback 오류
    llm_used: bool
    llm_error: str | None
    # 구매팀에 제안할 대응방안
    recommended_actions: list[str]

    # 구매팀에 제공할 최종 브리핑
    briefing: str

    # =========================================================
    # 6. Supervisor / Reviewer
    # =========================================================

    # Supervisor가 다음에 실행할 작업
    # 예: erp, contract, risk, response, reviewer, finish
    supervisor_next: str

    # Reviewer 검증 결과
    review_passed: bool | None

    # 수정해야 할 담당 영역
    # 예: erp, contract, response
    error_owner: str | None

    # 재실행 횟수와 검증 경고
    retry_count: int
    warnings: list[str]

    # =========================================================
    # 7. 기존 코드와의 임시 호환 필드
    # =========================================================

    # 기존 테스트와 노드가 사용 중이므로 당장은 유지
    # 이후 새 필드 전환이 끝나면 제거할 예정
    severity_tier: RiskLevel
    severity_score: int
    erp_findings: dict
    validation_passed: bool
