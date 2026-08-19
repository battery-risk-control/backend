package com.example.batteryrisk.repository;

/**
 * 화면 전체가 공유하는 <b>완결 NEWS 사건</b> 한 가지 정의.
 *
 * <p>KPI·지도·주요 알림·원자재 점수가 각자 다른 조건으로 뉴스를 세다가 같은 시점에 서로 다른
 * 숫자를 내보였다(실측 2026-08-03: 지도 주의 3 · KPI 주의 2 · 주요 알림 2). 조건을 여기 한 곳에만
 * 두고 전부 참조하게 한다 — 화면마다 조건을 베껴 두면 한쪽만 고쳐질 때 다시 갈린다.
 *
 * <p><b>완결</b>은 멀티에이전트와 reviewer까지 끝나 화면에 내보낼 수 있는 브리핑을 뜻한다.
 * 조기 종료({@code composite=false})는 점수가 0·정상으로 남고, 본문이 없으면 열어도 빈 화면이며,
 * reviewer가 문제를 찾은 건은 확정으로 부를 수 없다.
 *
 * <p><b>{@code source_type='NEWS'}로 좁히는 것이 핵심이다.</b> 계약·자재 화면에서 만든 브리핑도
 * 외부신호로 같은 뉴스를 끌어다 쓰므로 {@code analysis_id}가 겹치는데, 그건 그 계약의 브리핑이지
 * 그 뉴스의 브리핑이 아니다. 좁히지 않으면 뉴스 KPI가 계약 평가에 덮인다(실측: 리튬의 뉴스
 * 브리핑은 주의 48점인데 3시간 뒤 만들어진 계약 브리핑 정상 23점이 KPI를 차지했다).
 *
 * <p><b>{@code raw_events} 연결을 요구한다.</b> 수집을 거치지 않고 만들어진 분석(시연 시드·수동
 * {@code /analyze} 호출)은 운영 데이터로 보지 않는다. 요구하지 않으면 지도({@code analyses}에서
 * 출발)에는 뜨는데 목록({@code raw_events}에서 출발)에는 없는 사건이 생긴다 — 두 화면이 갈리던
 * 실제 원인이다(실측: LITHIUM/CL 완결 브리핑 4건이 전부 raw_event 없는 분석이었다). 이렇게
 * 걸러진 건은 사라지지 않고 {@link DashboardRepository#countOrphanNewsBriefings()}가 따로 센다.
 */
public final class NewsEventSql {

    /**
     * 사건 식별 키 — <b>정규화한 원문 제목 · 자재 대분류 · 국가</b>.
     *
     * <p>{@code analysis_id}로는 접히지 않는다. 같은 기사를 자동 트리거·스케줄러·수동 실행이
     * 반복 분석하면 {@code analyses} 행 자체가 따로 생기기 때문이다(실측: 리튬 평가 5건이 서로
     * 다른 분석 3건에서 나왔고 제목은 전부 같았다).
     *
     * <p>번역본({@code title_ko})이 아니라 원문({@code title})으로 접는다. 번역은 실행할 때마다
     * 달라져 같은 기사가 서로 다른 한국어 제목으로 저장돼 있다 — 실측한 DRC-르완다 기사 3건은
     * 번역 제목이 전부 다르고 원문 91자는 완전히 같았다.
     *
     * <p>제목 일부 일치로 합치지 않는다. 정규화(trim · 소문자 · 연속 공백 1개) 후 <b>전체</b>가
     * 같아야 같은 사건이다. 서로 다른 사건을 합치는 쪽이 누락보다 위험하다.
     *
     * <p>자재·국가를 함께 넣는 이유는 같은 제목이 서로 다른 자재·국가로 분류된 경우 다른 사건으로
     * 다뤄야 하기 때문이다. 화면이 자재별·국가별로 나뉘어 있어 하나로 접으면 한쪽에서 사라진다.
     */
    static final String EVENT_KEY =
            "regexp_replace(btrim(lower(COALESCE(r.title, r.title_ko))), '\\s+', ' ', 'g')"
                    + " || '|' || COALESCE(an.material_category, '')"
                    + " || '|' || COALESCE(an.country_code, '')";

    /**
     * 완결 NEWS 사건의 <b>원본 행</b>. 한 사건이 여러 번 분석됐으면 분석 수만큼 행이 나온다.
     *
     * <p>접는 것은 {@link #DEDUPED_NEWS_EVENTS_CTE}다. 사건에 딸린 평가를 전부 훑어야 하는 쪽
     * (원자재 점수)은 접기 전 이 CTE를 쓴다 — 먼저 접으면 대표로 뽑힌 분석에 평가 행이 없을 때
     * 그 사건이 통째로 사라진다(실측: 코발트의 최신 분석 a391238a에는 평가 행이 없다).
     */
    public static final String COMPLETED_NEWS_CTE = """
            completed_news AS (
                SELECT b.analysis_id,
                       b.briefing_id,
                       b.procurement_risk_level,
                       b.created_at AS briefing_created_at,
                       an.material_category,
                       an.country_code,
                       r.id AS event_id,
                       r.collected_at,
                       COALESCE(r.title_ko, an.event_title, r.title) AS display_title,
            """ + EVENT_KEY + """
                       AS event_key
                FROM ai_briefings b
                JOIN analyses an ON an.analysis_id = b.analysis_id
                JOIN raw_events r ON r.triggered_analysis_id = b.analysis_id
                WHERE b.source_type = 'NEWS'
                  AND b.composite = TRUE
                  AND NULLIF(BTRIM(b.briefing_text), '') IS NOT NULL
                  AND b.review_passed = TRUE
            )""";

    /**
     * 사건당 1건으로 접은 완결 NEWS 사건. 지도 마커와 사건 수를 세는 쪽이 쓴다.
     *
     * <p>사건 안에서는 <b>가장 최근 브리핑</b>을 남긴다 — 같은 사건을 다시 분석했다면 그건 대안이
     * 아니라 같은 것의 새 판이다.
     *
     * <p>{@link #COMPLETED_NEWS_CTE}가 앞에 함께 선언돼 있어야 한다.
     */
    static final String DEDUPED_NEWS_EVENTS_CTE = """
            news_events AS (
                SELECT DISTINCT ON (event_key) *
                FROM completed_news
                ORDER BY event_key, briefing_created_at DESC
            )""";

    private NewsEventSql() {
    }
}
