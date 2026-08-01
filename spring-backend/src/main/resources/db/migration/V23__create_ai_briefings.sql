-- 1계층 구매팀 "AI 브리핑" 화면에서 생성한 브리핑 본문과 근거.
--
-- V18(procurement_risk_assessments)이 남기는 것은 점수뿐이다. 브리핑 본문·권고조치·계약 근거는
-- 멀티에이전트 응답에만 있다가 사라져서, 화면이 "최근 브리핑"을 보여주거나 나중에 다시 열어볼 수
-- 없었다(CLAUDE.md 미결 항목). 그 본문을 담는 테이블이다.
--
-- 왜 procurement_risk_assessments에 컬럼을 붙이지 않았나:
--   그쪽은 스케줄러·리스크 모니터링·원자재 위험 등 <b>모든</b> 멀티에이전트 실행이 쌓이는 점수 이력이다.
--   "최근 브리핑" 목록은 사용자가 AI 브리핑 화면에서 직접 만든 것만 보여야 하므로, 같은 테이블을
--   쓰면 목록을 만들 때마다 "화면에서 만든 것"을 가려내는 조건이 필요해진다. 입자가 다르므로 분리한다.
--   대신 assessment_id로 점수 이력과 연결해 계보(어떤 점수 계산의 산출물인지)는 그대로 유지한다.
CREATE TABLE ai_briefings (
    briefing_id             UUID PRIMARY KEY,

    -- 이 브리핑을 만든 멀티에이전트 실행이 남긴 점수 행. 세부 점수·가중치 버전은 그쪽에 있다.
    assessment_id           UUID,

    -- 어느 화면에서 넘어왔는지. 화면이 "분석 대상" 칸의 라벨을 고를 때도 쓴다.
    source_type             VARCHAR(20)  NOT NULL,
    -- 그 화면이 넘긴 식별자 원본(eventId · erp_material_id · contract_id). 문자열로 통일해 둔다.
    source_ref              VARCHAR(100) NOT NULL,

    -- 화면 상단 "분석 대상". 뉴스 제목이거나 자재명이다.
    subject_title           VARCHAR(500) NOT NULL,
    -- 상단 부제로 쓰는 식별자. 멀티에이전트에 넘긴 news_id와 같은 값이다.
    news_id                 VARCHAR(200) NOT NULL,
    -- 외부신호(가중치 0.35)의 출처. analyses 행이다.
    analysis_id             UUID,
    -- 외부신호로 쓴 기사 제목. 자재·계약에서 넘어온 브리핑은 subject_title과 다르다
    -- (분석 대상은 자재/계약인데 외부신호는 별개의 저장된 뉴스에서 끌어오기 때문).
    source_headline         VARCHAR(500),

    -- 화면 상단 "ERP 연결" 3종.
    erp_material_id         VARCHAR(40),
    erp_supplier_id         VARCHAR(40),
    erp_contract_id         VARCHAR(40),
    -- RAG 검색 필터로 쓰이는 내부 계약 PK. erp_contract_id(CTR-010)와 다른 값이다.
    contract_id             BIGINT,
    material_name           VARCHAR(200),
    material_category       VARCHAR(50),
    impact_domain_final     VARCHAR(50),

    -- 우측 "분석 근거" 4칸. 화면이 JSONB를 파싱하지 않고 바로 읽도록 전용 컬럼으로 꺼내 둔다.
    external_signal_level   VARCHAR(20),
    external_signal_score   NUMERIC(5, 1),
    erp_exposure_level      VARCHAR(20),
    erp_exposure_score      NUMERIC(5, 1),
    contract_gap_score      NUMERIC(5, 1),
    -- "계약 RAG · 3개 조항"의 3. contract_findings 길이지만 목록 조회에서 JSONB를 열지 않으려고 둔다.
    contract_clause_count   INTEGER      NOT NULL DEFAULT 0,
    procurement_risk_level  VARCHAR(20)  NOT NULL,
    procurement_risk_score  NUMERIC(5, 1) NOT NULL,

    -- ERP·계약 노드까지 실제로 돌아 종합 점수가 나온 실행인지.
    -- false면 KG 게이트에서 조기 종료된 것이라 점수가 항상 0 · NORMAL로 남는다 —
    -- "평가해보니 정상"이 아니라 "평가하지 못했다"는 뜻이므로 화면이 구분해서 보여줘야 한다.
    composite               BOOLEAN      NOT NULL DEFAULT FALSE,

    -- 화면 본문.
    briefing_text           TEXT,
    risk_reasons            JSONB        NOT NULL,
    recommended_actions     JSONB        NOT NULL,
    -- "ERP 노출 근거" 한 줄(재고일수·안전재고·예상 입고·공급 공백·의존도)의 원본 값.
    erp_evidence            JSONB,
    -- "계약서에서 확인된 근거"(contract_id · page · 조항 본문).
    contract_findings       JSONB        NOT NULL,
    warnings                JSONB        NOT NULL,

    -- "검증 메타데이터" 칸.
    review_passed           BOOLEAN,
    llm_used                BOOLEAN      NOT NULL DEFAULT FALSE,
    llm_error               TEXT,
    weight_version          VARCHAR(50),
    mock                    BOOLEAN      NOT NULL DEFAULT FALSE,

    -- 누가 눌렀는지. 목록은 팀 전체 공용이라 필터에 쓰지 않고 감사 추적용으로만 남긴다.
    created_by              VARCHAR(255),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_ai_briefing_source
        CHECK (source_type IN ('NEWS', 'MATERIAL', 'CONTRACT')),
    -- risk_node.score_to_level은 critical/warning/normal 셋만 반환한다(V18과 같은 제약).
    CONSTRAINT ck_ai_briefing_level
        CHECK (procurement_risk_level IN ('NORMAL', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_ai_briefing_score
        CHECK (procurement_risk_score BETWEEN 0 AND 100)
);

-- "최근 브리핑" 목록 전용. 이 화면의 기본 조회가 최신순 N건이다.
CREATE INDEX idx_ai_briefing_created
    ON ai_briefings (created_at DESC);

-- 같은 뉴스로 만든 브리핑을 되짚을 때.
CREATE INDEX idx_ai_briefing_analysis
    ON ai_briefings (analysis_id);

COMMENT ON TABLE ai_briefings IS
    '1계층 AI 브리핑 화면이 생성·저장한 구매 위험 브리핑 본문과 근거';
