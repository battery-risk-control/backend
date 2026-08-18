package com.example.batteryrisk;

import com.example.batteryrisk.repository.RawEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리스크 모니터링 서버 페이지네이션(2026-08-16)의 <b>판정식 동기화 가드</b>.
 *
 * <p>등급·신뢰도 필터가 SQL로 내려가면서 판정식이 두 곳에 존재하게 됐다 — 화면 표시는
 * {@code RiskMonitoringService.toItem}(Java: gradeOf·newsConfidenceLabel), 목록 필터·건수는
 * {@code findRiskMonitoringPage}/{@code countRiskMonitoringEvents}(SQL). 두 식이 어긋나면
 * "심각 필터를 켰는데 주의 배지가 섞여 나오는" 화면이 된다. H2 테스트 DB는 DISTINCT ON·
 * LATERAL을 지원하지 않아 SQL을 실행해 비교할 수 없으므로, {@code MaterialKeywordSyncTest}와
 * 같은 방식으로 쿼리 <b>텍스트</b>가 Java 식과 같은 구조·상수를 쓰는지 검사한다.
 * (실행 동등성은 로컬 postgres 실측으로 확인했다 — docs/loading-optimization 참조.)
 */
class RiskMonitoringPaginationTest {

    /** 등급 필터: 화면 등급의 근거와 같은 COALESCE(브리핑등급, severity)를 필터에도 써야 한다. */
    @Test
    void gradeFilterUsesSameEffectiveLevelAsDisplay() {
        for (String sql : queries()) {
            assertThat(sql)
                    .as("등급 필터는 toItem의 gradeOf(analysis, briefing.risk_level)와 같은 우선순위여야 한다")
                    .contains("COALESCE(nb_level, an_severity)");
        }
    }

    /**
     * 신뢰도 필터: newsConfidenceLabel과 같은 구조(완결 브리핑→확정, score>=기준→경고, 그 외 참고)
     * 여야 하고, 기준값은 하드코딩이 아니라 :warnMin 파라미터(서비스가 같은 상수를 넘김)여야 한다.
     */
    @Test
    void confidenceFilterMirrorsNewsConfidenceLabel() {
        for (String sql : queries()) {
            assertThat(sql).contains("WHEN confirmed THEN '확정'");
            assertThat(sql).contains("WHEN an_severity_score >= :warnMin THEN '경고'");
            assertThat(sql).contains("ELSE '참고'");
        }
    }

    /** 완결 브리핑 판정이 NewsEventSql.COMPLETED_NEWS_CTE(지도·KPI 기준)와 같은 조건이어야 한다. */
    @Test
    void completedBriefingConditionMatchesSharedDefinition() {
        for (String sql : queries()) {
            assertThat(sql).contains("b.source_type = 'NEWS'");
            assertThat(sql).contains("b.composite = TRUE");
            assertThat(sql).contains("NULLIF(BTRIM(b.briefing_text), '') IS NOT NULL");
            assertThat(sql).contains("b.review_passed = TRUE");
        }
    }

    /** 목록과 건수는 같은 필터 블록을 써야 마지막 페이지 화살표 잠금이 정확하다. */
    @Test
    void listAndCountShareIdenticalFilterBlock() {
        String list = filterBlockOf(queryOf("findRiskMonitoringPage"));
        String count = filterBlockOf(queryOf("countRiskMonitoringEvents"));
        assertThat(count).isEqualTo(list);
    }

    private static String[] queries() {
        return new String[] {queryOf("findRiskMonitoringPage"), queryOf("countRiskMonitoringEvents")};
    }

    /** WHERE 이후(정렬·LIMIT 제외)의 필터 블록만 뽑아 비교한다. */
    private static String filterBlockOf(String sql) {
        int from = sql.lastIndexOf("WHERE (CAST(:severityLevel");
        assertThat(from).as("등급/신뢰도 필터 블록이 있어야 한다").isPositive();
        String tail = sql.substring(from);
        int order = tail.indexOf("ORDER BY");
        return (order > 0 ? tail.substring(0, order) : tail).strip();
    }

    private static String queryOf(String methodName) {
        Method method = Arrays.stream(RawEventRepository.class.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("메서드 없음: " + methodName));
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("@Query가 있어야 한다: " + methodName).isNotNull();
        return query.value();
    }

    /** 시그니처가 바뀌면(파라미터 추가 등) 이 테스트도 함께 갱신하라는 컴파일 타임 앵커. */
    @SuppressWarnings("unused")
    private void signatureAnchor(RawEventRepository repository) {
        repository.findRiskMonitoringPage(Instant.now(), null, null, null, null, 0.0, 1, 0);
        repository.countRiskMonitoringEvents(Instant.now(), null, null, null, null, 0.0);
    }
}
