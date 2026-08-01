-- 브리핑 산출물이 응답으로만 나가고 저장은 안 되던 문제 해결.
--
-- generate_briefing_node(FastAPI)는 use_llm과 무관하게 항상 브리핑을 만든다 — use_llm은
-- "만들까 말까"가 아니라 "LLM으로 쓸까, 템플릿으로 조립할까"의 선택이다. 그런데 V18에는
-- 저장할 컬럼이 없어 점수와 달리 브리핑 본문은 여전히 응답으로만 나가고 사라지고 있었다.
--
-- use_llm=false인 지금도 템플릿 조립본이 이 컬럼들에 저장되고, 나중에 true로 켜면 LLM
-- 생성본이 같은 자리에 그대로 들어간다 — 스키마 변경 없이 전환된다.
ALTER TABLE procurement_risk_assessments
    ADD COLUMN briefing             TEXT,
    ADD COLUMN recommended_actions  JSONB,
    -- 인용한 계약 조항. 감사추적의 핵심 — "이 등급이 왜 나왔는지"뿐 아니라
    -- "브리핑이 어느 조항을 근거로 그 문장을 썼는지"까지 재현할 수 있어야 한다.
    ADD COLUMN contract_findings    JSONB,
    ADD COLUMN warnings             JSONB;
