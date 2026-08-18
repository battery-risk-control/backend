package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.RawEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RawEventRepository extends JpaRepository<RawEvent, Long> {
    boolean existsBySourceAndExternalId(String source, String externalId);

    boolean existsBySourceAndContentHash(String source, String contentHash);

    /**
     * 공개 뉴스 속보 패널용 최신 뉴스. title이 null인 행은 GDELT 커서 전진용 sentinel이라 제외한다
     * (GdeltRealtimeTriageAdapter 참고) — 화면에 빈 헤드라인이 뜨는 것을 막는다.
     */
    List<RawEvent> findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(String dataType, Pageable pageable);

    /**
     * 리스크 모니터링 목록 후보. <b>필터와 중복 제거를 SQL에서 끝낸 뒤</b> 최신순으로 자른다.
     *
     * <p>예전에는 최신 400건을 먼저 가져와 Java에서 걸렀는데, 그러면 관련 뉴스가 그 창 밖으로
     * 밀려나 조회조차 되지 않았다 — 실측(최근 7일): 자재가 분류된 고유 뉴스가 14건인데 최신
     * 400건 안에는 4건뿐이었다. GDELT가 공급망과 무관한 기사를 대량으로 통과시키기 때문에
     * 고정 창 방식은 데이터가 쌓일수록 더 나빠진다.
     *
     * <p>자재가 특정되지 않은 기사는 제외한다({@code material_category IS NOT NULL}). 구매팀
     * 화면에 정치·사건사고 기사가 올라오면 목록이 쓸모없어진다.
     *
     * <p>중복 제거 키는 {@link NewsEventSql#EVENT_KEY 공통 사건 키}다 — 정규화한 원문 제목에
     * 자재·국가를 더한 값이다. 같은 사건이 GDELT에서 여러 번 보고되며 대소문자·공백만 다른
     * 제목으로 들어온다. 같은 사건 중에서는 최신 수집분을 남긴다.
     *
     * <p>지도가 쓰는 키와 <b>같은 것</b>이라는 점이 중요하다. 예전에는 여기가 제목만 보고 지도는
     * {@code (국가, 자재)}로 접어서, 같은 시점에 지도가 주의 3건 · 주요 알림이 2건을 보여줬다
     * (실측 2026-08-03). 주요 알림은 이 목록에서 파생되므로 여기를 맞추면 함께 맞는다.
     */
    /**
     * 등급·신뢰도 필터까지 SQL에서 끝내는 이유(2026-08-16, 서버 페이지네이션 도입): 둘을 Java에서
     * 거르면 OFFSET이 "거르기 전" 행 기준이 되어, 등급 필터를 켠 채 페이지를 넘기면 항목이
     * 겹치거나 빠진다(뉴스 피드가 중복 제거를 SQL로 내린 것과 같은 원리). 판정식은 화면 표시식과
     * 반드시 같아야 한다:
     *  - 등급: {@code COALESCE(완결브리핑.procurement_risk_level, an.severity)} —
     *    {@code RiskMonitoringService.toItem}의 gradeOf(analysis, riskLevel)와 동일. 화면 등급명
     *    (심각/주의/정상)→severity 코드 변환은 {@code RiskEventService.severityForGrade}가 하나의
     *    표(GRADE_BY_SEVERITY)에서 도출해 넘긴다.
     *  - 신뢰도: 완결 브리핑 있으면 '확정', 아니면 severity_score >= :warnMin 이면 '경고', 그 외
     *    '참고' — {@code RiskEventService.newsConfidenceLabel}과 동일하며 :warnMin은 같은 상수
     *    (EXTERNAL_SIGNAL_WARNING_MIN)를 서비스가 넘긴다.
     *  두 식이 어긋나면 "목록에는 있는데 배지가 다른" 행이 생기므로
     *  {@code RiskMonitoringPaginationTest}가 SQL 필터 결과와 Java 표시 결과의 일치를 검사한다.
     *
     * <p>완결 브리핑 판정은 {@link NewsEventSql#COMPLETED_NEWS_CTE}와 같은 조건(NEWS·composite·
     * 본문 존재·review_passed, 최신 1건)이다 — 지도·KPI와 확정 기준이 갈리지 않게 한다.
     *
     * <p>정렬에 {@code id DESC}를 보조 키로 두는 이유: {@code collected_at}이 같은 행이 있으면
     * OFFSET 페이지 경계가 실행마다 흔들려 항목이 겹치거나 빠질 수 있다. 보조 키로 순서를
     * 결정적으로 만든다.
     */
    @Query(nativeQuery = true, value = """
            WITH candidates AS (
                SELECT r.*, an.material_category,
                       an.severity AS an_severity,
                       an.severity_score AS an_severity_score,
                       an.analysis_id AS an_analysis_id,
            """ + NewsEventSql.EVENT_KEY + """
                       AS event_key
                FROM raw_events r
                JOIN analyses an ON an.analysis_id = r.triggered_analysis_id
                WHERE r.data_type = 'NEWS'
                  AND r.title IS NOT NULL
                  AND r.collected_at >= :since
                  AND an.material_category IS NOT NULL
                  AND (CAST(:country AS VARCHAR) IS NULL OR r.country_code = CAST(:country AS VARCHAR))
                  AND (CAST(:materialCategory AS VARCHAR) IS NULL
                       OR an.material_category = CAST(:materialCategory AS VARCHAR))
            ),
            deduped AS (
                SELECT DISTINCT ON (event_key) *
                FROM candidates
                ORDER BY event_key, collected_at DESC
            ),
            graded AS (
                SELECT d.*, nb.procurement_risk_level AS nb_level,
                       (nb.briefing_id IS NOT NULL) AS confirmed
                FROM deduped d
                LEFT JOIN LATERAL (
                    SELECT b.briefing_id, b.procurement_risk_level
                    FROM ai_briefings b
                    WHERE b.analysis_id = d.an_analysis_id
                      AND b.source_type = 'NEWS'
                      AND b.composite = TRUE
                      AND NULLIF(BTRIM(b.briefing_text), '') IS NOT NULL
                      AND b.review_passed = TRUE
                    ORDER BY b.created_at DESC
                    LIMIT 1
                ) nb ON TRUE
            )
            SELECT * FROM graded
            WHERE (CAST(:severityLevel AS VARCHAR) IS NULL
                   OR COALESCE(nb_level, an_severity) = CAST(:severityLevel AS VARCHAR))
              AND (CAST(:confidence AS VARCHAR) IS NULL
                   OR (CASE WHEN confirmed THEN '확정'
                            WHEN an_severity_score >= :warnMin THEN '경고'
                            ELSE '참고' END) = CAST(:confidence AS VARCHAR))
            ORDER BY collected_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """)
    List<RawEvent> findRiskMonitoringPage(
            Instant since, String country, String materialCategory,
            String severityLevel, String confidence, double warnMin,
            int limit, int offset);

    /**
     * {@link #findRiskMonitoringPage}와 같은 조건의 전체 건수. 화면이 마지막 페이지에서 화살표를
     * 정확히 잠그려면 "다음이 있는지"가 아니라 전체가 몇 건인지 알아야 한다(뉴스 피드
     * {@code countSupplyChainNews}와 같은 취지). 목록과 반드시 같은 조건을 유지할 것.
     */
    @Query(nativeQuery = true, value = """
            WITH candidates AS (
                SELECT r.id, r.collected_at,
                       an.severity AS an_severity,
                       an.severity_score AS an_severity_score,
                       an.analysis_id AS an_analysis_id,
            """ + NewsEventSql.EVENT_KEY + """
                       AS event_key
                FROM raw_events r
                JOIN analyses an ON an.analysis_id = r.triggered_analysis_id
                WHERE r.data_type = 'NEWS'
                  AND r.title IS NOT NULL
                  AND r.collected_at >= :since
                  AND an.material_category IS NOT NULL
                  AND (CAST(:country AS VARCHAR) IS NULL OR r.country_code = CAST(:country AS VARCHAR))
                  AND (CAST(:materialCategory AS VARCHAR) IS NULL
                       OR an.material_category = CAST(:materialCategory AS VARCHAR))
            ),
            deduped AS (
                SELECT DISTINCT ON (event_key) *
                FROM candidates
                ORDER BY event_key, collected_at DESC
            ),
            graded AS (
                SELECT d.*, nb.procurement_risk_level AS nb_level,
                       (nb.briefing_id IS NOT NULL) AS confirmed
                FROM deduped d
                LEFT JOIN LATERAL (
                    SELECT b.briefing_id, b.procurement_risk_level
                    FROM ai_briefings b
                    WHERE b.analysis_id = d.an_analysis_id
                      AND b.source_type = 'NEWS'
                      AND b.composite = TRUE
                      AND NULLIF(BTRIM(b.briefing_text), '') IS NOT NULL
                      AND b.review_passed = TRUE
                    ORDER BY b.created_at DESC
                    LIMIT 1
                ) nb ON TRUE
            )
            SELECT COUNT(*) FROM graded
            WHERE (CAST(:severityLevel AS VARCHAR) IS NULL
                   OR COALESCE(nb_level, an_severity) = CAST(:severityLevel AS VARCHAR))
              AND (CAST(:confidence AS VARCHAR) IS NULL
                   OR (CASE WHEN confirmed THEN '확정'
                            WHEN an_severity_score >= :warnMin THEN '경고'
                            ELSE '참고' END) = CAST(:confidence AS VARCHAR))
            """)
    long countRiskMonitoringEvents(
            Instant since, String country, String materialCategory,
            String severityLevel, String confidence, double warnMin);


    /** 위와 같되 국가로 좁힌다. 지도 마커를 클릭했을 때 그 국가 뉴스만 보여주는 경로에서 쓴다. */
    List<RawEvent> findByDataTypeAndCountryCodeAndTitleIsNotNullOrderByCollectedAtDesc(
            String dataType, String countryCode, Pageable pageable);

    /**
     * 자재 키워드가 제목·본문에 있거나(리터럴), 분석이 자재로 분류한(analysis.material_category)
     * 뉴스만, 제목 중복을 제거해 최신순으로.
     *
     * <p><b>키워드 OR 분석분류인 이유</b>: 리터럴 키워드만 보면, 분석은 특정 자재로 분류했지만
     * 제목·본문엔 그 단어가 없는 기사(예: "라인강 저수위" → 구리 바지선 물류)가 피드에서 빠져,
     * 같은 사건을 리스크 모니터링·지도(둘 다 {@code material_category} 기준)는 보여주는데 피드만
     * 안 보이는 어긋남이 생긴다. 그래서 {@code analyses}를 LEFT JOIN해 분석분류 뉴스도 포함한다.
     * (raw_event당 {@code triggered_analysis_id → analysis_id} 매칭이 최대 1건이라 행 증식·페이징
     * 영향 없음. 합집합이라 기존 키워드 뉴스는 그대로 유지된다.)
     *
     * <p><b>필터를 SQL로 내린 이유</b>: 예전에는 최근 200건을 통째로 읽어 Java에서 걸렀는데,
     * 본문 평균이 4.8천자라 요청마다 약 1MB를 옮기면서 정작 통과하는 건 2%대였다. 페이지를
     * 넘기려면 스캔 범위를 더 키워야 해서 그 비용이 페이지 수만큼 늘어난다. 여기서 거르면
     * 실제로 보여줄 행만 오간다.
     *
     * <p>자재 매칭은 {@code e.material_matched}(V38 생성 컬럼 + 부분 인덱스)로 판정한다. 예전에는
     * 요청마다 {@code (title || content) ~* <키워드 정규식>}으로 테이블을 통째로 훑어 약 1.1초가
     * 들었다(실측). 매칭 여부는 행이 만들어질 때 정해지므로 수집 시점에 굳혀 두고 인덱스로 읽는다.
     * 키워드 집합은 그 컬럼 식(V38)과 {@code RiskEventService.MATERIAL_KEYWORDS}가 동기화되어야
     * 하며, {@code RiskEventServiceTest}가 이를 검사한다.
     *
     * <p>{@code DISTINCT ON (lower(trim(title)))}으로 같은 기사가 GDELT GlobalEventID만 다른 채로
     * 여러 번 들어온 것을 접는다. 중복 제거를 Java에서 하면 OFFSET이 어긋난다 — 20건을 건너뛴
     * 뒤에 중복이 걸러지면 실제로는 20건보다 적게 넘어간 셈이 되어 페이지마다 항목이 겹친다.
     */
    // [ROLLBACK] 2026-08-14 이전 정규식 버전. material_matched(V38)에 문제가 생기면 아래 WHERE의
    //   AND (e.material_matched OR an.material_category IS NOT NULL)
    // 를 다음으로 되돌리고, 시그니처에 String keywordPattern 을 다시 추가한 뒤
    // RiskEventService.materialKeywordPattern() 을 넘기면 된다(그 메서드는 @Deprecated로 보존됨):
    //   AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
    //        OR an.material_category IS NOT NULL)
    @Query(nativeQuery = true, value = """
            SELECT * FROM (
                SELECT DISTINCT ON (lower(trim(e.title))) e.*
                FROM raw_events e
                LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
                WHERE e.data_type = 'NEWS'
                  AND e.title IS NOT NULL
                  AND (:countryCode IS NULL OR e.country_code = :countryCode)
                  AND (e.material_matched OR an.material_category IS NOT NULL)
                  -- 최신 뉴스 노출 만료: 등급별로 심각 10일 · 주의 5일 · 그 외(참고/미분석) 3일이
                  -- 지나면 목록에서 제외한다. 등급은 뉴스 속보와 같은 출처(완결된 NEWS 브리핑의
                  -- procurement_risk_level)에서 읽어 화면 배지와 어긋나지 않게 한다.
                  AND e.collected_at >= now() - (
                      CASE (
                          SELECT b.procurement_risk_level FROM ai_briefings b
                          WHERE b.analysis_id = an.analysis_id
                            AND b.source_type = 'NEWS' AND b.composite = TRUE
                            AND b.briefing_text IS NOT NULL AND b.review_passed = TRUE
                          ORDER BY b.created_at DESC LIMIT 1
                      )
                      WHEN 'CRITICAL' THEN INTERVAL '10 days'
                      WHEN 'WARNING'  THEN INTERVAL '5 days'
                      ELSE INTERVAL '3 days'
                      END
                  )
                ORDER BY lower(trim(e.title)), e.collected_at DESC
            ) deduped
            ORDER BY deduped.collected_at DESC
            LIMIT :limit OFFSET :offset
            """)
    List<RawEvent> findSupplyChainNews(
            String countryCode, int limit, int offset);

    /**
     * {@link #findSupplyChainNews} 조건에 맞는 전체 건수. 화면이 "다음 페이지가 있는지"가 아니라
     * "전부 몇 건인지"를 알아야 마지막 페이지에서 화살표를 정확히 잠글 수 있다.
     */
    // [ROLLBACK] findSupplyChainNews와 동일 — material_matched(V38) 문제 시 아래 WHERE의
    //   AND (e.material_matched OR an.material_category IS NOT NULL)
    // 를 정규식 버전으로 되돌리고 String keywordPattern 인자를 복원한다:
    //   AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
    //        OR an.material_category IS NOT NULL)
    @Query(nativeQuery = true, value = """
            SELECT COUNT(DISTINCT lower(trim(e.title)))
            FROM raw_events e
            LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
            WHERE e.data_type = 'NEWS'
              AND e.title IS NOT NULL
              AND (:countryCode IS NULL OR e.country_code = :countryCode)
              AND (e.material_matched OR an.material_category IS NOT NULL)
              -- findSupplyChainNews와 같은 만료 규칙(심각 10일·주의 5일·그 외 3일). 목록과 총건수가
              -- 어긋나면 마지막 페이지 화살표 잠금이 틀어지므로 두 쿼리를 반드시 동일 조건으로 둔다.
              AND e.collected_at >= now() - (
                  CASE (
                      SELECT b.procurement_risk_level FROM ai_briefings b
                      WHERE b.analysis_id = an.analysis_id
                        AND b.source_type = 'NEWS' AND b.composite = TRUE
                        AND b.briefing_text IS NOT NULL AND b.review_passed = TRUE
                      ORDER BY b.created_at DESC LIMIT 1
                  )
                  WHEN 'CRITICAL' THEN INTERVAL '10 days'
                  WHEN 'WARNING'  THEN INTERVAL '5 days'
                  ELSE INTERVAL '3 days'
                  END
              )
            """)
    long countSupplyChainNews(String countryCode);

    /**
     * 최신 공급망 뉴스의 수집 시각(대시보드 "기준 시각"). {@link #findSupplyChainNews}가 보여주는
     * 목록 최상단 기사의 {@code collected_at}과 같은 값이다 — 그 목록은 {@code collected_at DESC}
     * 정렬이라 최댓값이 곧 맨 위 항목이다. 만료 규칙(심각 10일·주의 5일·그 외 3일)은 오래된 항목만
     * 걷어내고 최신 항목은 항상 창 안에 있으므로 MAX에 영향을 주지 않아 생략한다. 없으면 {@code null}.
     */
    // [ROLLBACK] material_matched(V38) 문제 시 아래 WHERE의
    //   AND (e.material_matched OR an.material_category IS NOT NULL)
    // 를 정규식 버전으로 되돌리고 String keywordPattern 인자를 복원한다:
    //   AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
    //        OR an.material_category IS NOT NULL)
    @Query(nativeQuery = true, value = """
            SELECT MAX(e.collected_at)
            FROM raw_events e
            LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
            WHERE e.data_type = 'NEWS'
              AND e.title IS NOT NULL
              AND (e.material_matched OR an.material_category IS NOT NULL)
            """)
    Instant findLatestSupplyChainCollectedAt();

    /**
     * 분석에 대응하는 수집 원본. 지도 마커의 제목을 한국어로 보여주기 위해 쓴다 —
     * {@code analyses.event_title}은 GDELT 원문(영문)이고 번역본은 {@code raw_events.title_ko}에만
     * 있어서, 이 조인이 없으면 지도만 영문 제목이 뜬다(뉴스 속보는 이미 번역본을 쓴다).
     */
    List<RawEvent> findByTriggeredAnalysisIdIn(Collection<UUID> analysisIds);

    /**
     * 번역 대기 중인 뉴스. 최신순으로 집어간다 — 화면 상단(마퀴)에 뜨는 기사가 먼저 한국어가 되는 게
     * 체감상 중요하고, 오래된 기사는 어차피 목록 뒤로 밀린다.
     *
     * <p>title이 null인 행은 커서 전진용 sentinel이라 제외한다(GdeltRealtimeTriageAdapter 참고).
     */
    @Query("""
            SELECT e FROM RawEvent e
            WHERE e.dataType = 'NEWS'
              AND e.title IS NOT NULL
              AND e.titleKo IS NULL
              AND e.translationAttempts < :maxAttempts
            ORDER BY e.collectedAt DESC
            """)
    List<RawEvent> findUntranslatedNews(short maxAttempts, Pageable pageable);

    /**
     * 분석 UUID로 그 분석을 낳은 수집 이벤트를 되찾는다. AI 브리핑 화면이 자동 생성 브리핑
     * ({@code source_ref}가 분석 UUID)을 열 때 eventId를 복원하는 데 쓴다.
     */
    Optional<RawEvent> findFirstByTriggeredAnalysisId(UUID triggeredAnalysisId);

    Optional<RawEvent> findFirstByDataTypeAndCountryCodeOrderByCollectedAtDesc(String dataType, String countryCode);

    Optional<RawEvent> findFirstByDataTypeOrderByCollectedAtDesc(String dataType);

    long countByDataTypeAndCollectedAtAfter(String dataType, Instant after);

    long countByDataTypeAndCountryCode(String dataType, String countryCode);

    List<RawEvent> findByDataTypeOrderByCollectedAtAsc(String dataType);

    List<RawEvent> findByDataTypeAndCountryCodeAndCollectedAtBetween(
            String dataType, String countryCode, Instant start, Instant end);
}
