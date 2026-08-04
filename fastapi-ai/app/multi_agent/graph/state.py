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

    # 뉴스에서 추출한 영향 국가(ISO 코드)
    country: str

    # 뉴스와 외부 데이터만 사용한 1차 위험 신호
    external_signal_level: RiskLevel
    external_signal_score: int

    # =========================================================
    # 1-1. KG 리졸버(kg_service GET /resolve)
    # =========================================================

    # kg_service /resolve 원본 응답(재현/디버깅용)
    kg_context: dict | None

    # country+affected_materials가 KG 그래프에서 자재 카테고리로 매칭됐는지
    kg_matched: bool

    # 매칭된 카테고리 중 재고 부족(SHORTAGE)이 하나라도 있는지
    # — 이후 erp/contract/risk/response 단계를 태울지 결정하는 게이트
    kg_shortage_detected: bool

    # KG_GATE_ENABLED=false로 게이트를 우회했는지.
    #
    # kg_service가 떠 있지 않으면 resolve_kg_context가 "매칭 없음"으로 폴백하고 게이트가
    # 모든 건을 조기 종료시켜, 뒷단(ERP·계약·위험도·브리핑)을 확인할 방법이 없다. 이 플래그가
    # true면 supervisor가 조기 종료를 건너뛰고 erp부터 정상 경로를 태운다.
    #
    # **kg_shortage_detected를 true로 덮어쓰지 않는다.** 그 필드는 "KG가 재고부족을 확정했는가"라는
    # 사실 그대로 남겨야, 우회한 실행과 진짜 확정된 실행을 나중에 구분할 수 있다.
    kg_gate_bypassed: bool

    # KG가 원산지 매칭으로 좁힌 공급사/계약 식별자
    # (kg_service의 CSV 스냅샷 기준 외부 문자열 ID, ERP DB 내부 숫자 ID와는 다름)
    kg_affected_suppliers: list[str]
    kg_affected_contract_ids: list[str]
    kg_affected_outbound_contract_ids: list[str]

    # KG가 계산한 대체 공급사 후보와 Centrality*Magnitude 영향도 점수
    kg_alternative_suppliers: list[dict]
    kg_impact_score: float | None

    # KG가 생성한 한국어 근거 경로 문장(매칭된 카테고리별)
    kg_evidence_paths: list[str]

    # =========================================================
    # 2. ERP Exposure Agent
    # =========================================================

    # Spring Boot가 ERP DB에서 조회하여 전달하는 원본 데이터
    # FastAPI는 ERP DB에 직접 접근하지 않음
    erp_context: dict

    # ERP Agent가 계산한 회사 내부 노출도 분석 결과
    erp_assessment: dict

    # 수정님 ERP Exposure Agent의 원본 구조화 응답
    # Reviewer 검증과 Spring 응답 추적에 사용
    erp_exposure_response: dict

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

    # ERP↔Contract 협상 왕복 횟수. MAX_NEGOTIATION_ROUNDS(routing.py)까지
    # 라운드마다 +1 — 이전엔 불리언 게이트(erp_reassessment_done)로 딱 한 번만
    # 허용했지만, 이제 recheck_soojung_erp_node가 매 라운드 점수를 실제로
    # 재계산하고 수렴할 때까지(또는 상한까지) 여러 번 왕복할 수 있다.
    negotiation_round: int

    # ERP 재검토가 "그래도 아직 판단하기엔 근거가 부족하다"고 판단해서
    # Contract Agent에게 한 번 더 검색을 요청하는 질문. 비어있으면 협상 종료.
    questions_for_contract_agent_round2: list[str]

    # =========================================================
    # 3-1. Outbound Contract Agent (완성차 고객사 배상책임)
    # =========================================================

    # KG가 확정한 재고부족 원자재와 연결된 아웃바운드 계약들(PostgreSQL 내부 숫자 ID,
    # 재무 노출도 상위 N건). Spring이 kg_service /resolve로 미리 리졸브해서 실어 보낸다 —
    # 리졸브 실패(아웃바운드 계약 없음/매칭 안 됨) 시 빈 리스트이고, 이땐 아웃바운드
    # 조회 자체를 건너뛴다. 각 원소는 {"contract_id", "product_id", "customer_id"}.
    outbound_contracts: list[dict]

    # kg_service가 원래 돌려준 전체 매칭 건수(상세 검색 대상인 outbound_contracts보다
    # 클 수 있음) — 브리핑에서 "이 외 N건 더" 요약에 쓴다.
    outbound_contracts_total_matched: int

    # 검색된 아웃바운드 계약 조항(배상책임/지체상금 등)과 출처 근거.
    # contract_findings와 같은 형태지만 완성차 고객사 계약이라 별도로 둔다.
    outbound_contract_findings: list[dict]

    # outbound_contract 노드가 이미 실행됐는지. 라운드 왕복이 아니라 단발성 조회라
    # negotiation_round 같은 카운터 대신 불리언 가드로 충분하다.
    outbound_contract_checked: bool

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
