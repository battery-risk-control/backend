-- 멀티에이전트 구매 리스크 점수 이력.
--
-- 지금까지 POST /api/v1/multi-agent/briefings 결과는 응답으로만 나가고 사라졌다.
-- 재조회·감사추적이 불가능했고, 비로그인 대시보드는 페이지를 열 때마다 LangGraph를
-- 다시 돌릴 수 없으므로 미리 채워둘 저장소가 필요하다.
--
-- 입자: 뉴스 이벤트 × 자재. severity_assessments와 같은 append-only 이력이다.
-- 자재별로 접은 값(카드 1장에 해당하는 점수)은 저장하지 않는다 — 접는 규칙이 아직
-- 확정 전이라, 원본만 남겨두면 규칙을 바꿔도 과거까지 새 규칙으로 다시 접을 수 있다.
CREATE TABLE procurement_risk_assessments (
    assessment_id           UUID PRIMARY KEY,

    -- 계보. analysis_id는 외부신호 점수의 출처다(analyses.severity_score).
    analysis_id             UUID,
    news_id                 VARCHAR(200) NOT NULL,
    material_id             BIGINT,
    erp_material_id         VARCHAR(40),
    erp_supplier_id         VARCHAR(40),
    -- 자재 대분류 8종(LITHIUM/COBALT/NICKEL/GRAPHITE/MANGANESE/COPPER/ALUMINUM/RARE_EARTH).
    -- 화면 카드가 이 단위라 접기 인덱스도 여기에 건다. analyses가 없으면 채울 수 없어 NULL 허용.
    material_category       VARCHAR(50),
    impact_domain_final     VARCHAR(50),
    assessed_at             TIMESTAMPTZ NOT NULL,

    -- 세부 점수 3개. FastAPI 응답에서는 risk_reasons 문장("ERP 내부 노출도 점수: 45")과
    -- erp_assessment/contract_assessment Map 안에 묻혀 있어 화면에서 파싱해야 했다.
    -- 분해 표시를 바로 할 수 있도록 전용 컬럼으로 꺼내 둔다.
    external_signal_score   NUMERIC(5, 1) NOT NULL,
    external_signal_level   VARCHAR(20)   NOT NULL,
    erp_exposure_score      NUMERIC(5, 1),
    contract_gap_score      NUMERIC(5, 1),

    -- 종합. risk_node의 가중합(외부 0.35 + ERP 0.45 + 계약공백 0.20) 결과다.
    procurement_risk_score  NUMERIC(5, 1) NOT NULL,
    procurement_risk_level  VARCHAR(20)   NOT NULL,

    -- 재현용 근거.
    risk_reasons            JSONB NOT NULL,
    erp_assessment          JSONB,
    contract_assessment     JSONB,

    -- 가중치는 튜닝될 값이다. 버전 없이 종합 점수만 저장하면 나중에 과거 점수를 해석할 수 없다.
    weight_version          VARCHAR(50) NOT NULL,
    -- risk_node는 stockout_before_eta가 true면 가중합과 무관하게 심각으로 강제 상향한다.
    -- 왜 올라갔는지 남기지 않으면 점수와 등급이 어긋나 보인다.
    stockout_gate_applied   BOOLEAN NOT NULL DEFAULT FALSE,
    review_passed           BOOLEAN,
    llm_used                BOOLEAN NOT NULL DEFAULT FALSE,
    -- analyses.mock을 물려받는다. SeverityService.evaluate()처럼 상수 true로 두지 않는다
    -- (그쪽은 실데이터에도 mock=true가 찍혀 severity_assessments 전체가 mock으로 오염됐다).
    mock                    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- risk_node.score_to_level은 critical/warning/normal 셋만 반환한다.
    -- severity_assessments와 달리 UNKNOWN이 없다.
    CONSTRAINT ck_pra_level
        CHECK (procurement_risk_level IN ('NORMAL', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_pra_score
        CHECK (procurement_risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_pra_external_score
        CHECK (external_signal_score BETWEEN 0 AND 100),
    CONSTRAINT ck_pra_erp_score
        CHECK (erp_exposure_score IS NULL OR erp_exposure_score BETWEEN 0 AND 100),
    CONSTRAINT ck_pra_contract_score
        CHECK (contract_gap_score IS NULL OR contract_gap_score BETWEEN 0 AND 100)
);

-- 자재별 최신 N건 접기용. DashboardRepository의 LATEST_ASSESSMENT_CTE와 같은 접근 패턴이다.
CREATE INDEX idx_pra_category_created
    ON procurement_risk_assessments (material_category, created_at DESC);

-- 스케줄러가 "이미 평가한 analysis인가"를 확인할 때 쓴다(중복 실행 방지).
CREATE INDEX idx_pra_analysis
    ON procurement_risk_assessments (analysis_id);

COMMENT ON TABLE procurement_risk_assessments IS
    'LangGraph 멀티에이전트 구매 리스크 점수 이력(뉴스 x 자재, append-only)';
