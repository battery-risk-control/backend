-- reviewer가 환경 고지를 검증 실패로 세던 시절의 판정을 바로잡는다.
--
-- verification_node는 앞선 노드의 warnings를 이어받는다(의도된 동작 — 응답 스키마에 그 사실을
-- 실을 필드가 warnings뿐이라 지우면 사라진다). 그런데 review_passed = len(warnings) == 0 이라,
-- 브리핑을 다시 써도 사라지지 않는 **설정 고지**까지 실패로 집계됐다. 특히 KG_GATE_ENABLED를
-- false로 내린 2026-08-03부터는 모든 실행이 false로 뒤집혔다.
--
-- 코드는 고쳤다(이 노드가 새로 찾은 경고만 실패로 친다). 남은 건 그 시절에 저장된 행이다.
--
-- 왜 다시 돌리지 않고 값만 고치나:
--   그 버그는 **판정 플래그 하나**만 틀리게 만들었다. 점수·브리핑 본문·계약 근거는 전부 정상
--   경로로 계산된 값이라 다시 돌릴 이유가 없다. 재실행하면 LLM 비용이 또 나가고, 지금은 KG
--   게이트가 켜져 있어 그때와 다른 결과가 나올 수도 있다 — 과거 기록을 현재 설정으로 덮어쓰는
--   셈이라 감사 추적에도 좋지 않다.
--
-- 경고 자체는 지우지 않는다. "KG를 우회해서 만든 브리핑"이라는 건 여전히 사실이고 화면·감사에서
-- 보여야 한다. 고치는 건 "reviewer가 문제를 찾았는가"라는 판정뿐이다.

UPDATE ai_briefings
SET review_passed = TRUE
WHERE review_passed IS NOT TRUE
  -- 경고가 하나라도 있어야 한다. 경고 0건인데 false면 그건 이 버그가 아니라 다른 사유다.
  AND jsonb_array_length(warnings) > 0
  -- 모든 경고가 환경 고지여야 한다. 하나라도 reviewer 지적(근거 없음·금지 표현·권고 조치
  -- 누락 등)이 섞여 있으면 그 행은 진짜 실패이므로 건드리지 않는다.
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements_text(warnings) AS w(text)
      WHERE w.text NOT LIKE 'KG 게이트를 우회한 실행입니다%'
        AND w.text NOT LIKE '브리핑을 OpenAI로 생성했습니다%'
  );
