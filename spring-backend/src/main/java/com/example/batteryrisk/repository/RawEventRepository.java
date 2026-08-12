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
    @Query(nativeQuery = true, value = """
            WITH candidates AS (
                SELECT r.*, an.material_category,
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
            )
            SELECT * FROM deduped
            ORDER BY collected_at DESC
            LIMIT :limit
            """)
    List<RawEvent> findRiskMonitoringCandidates(
            Instant since, String country, String materialCategory, int limit);


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
     * <p>키워드는 {@code RiskEventService.MATERIAL_KEYWORDS}에서 만들어 넘긴다 — SQL에 목록을
     * 박아 두면 자재를 추가할 때 두 곳을 고쳐야 하고, 한쪽을 잊으면 화면과 분류가 조용히
     * 어긋난다.
     *
     * <p>{@code DISTINCT ON (lower(trim(title)))}으로 같은 기사가 GDELT GlobalEventID만 다른 채로
     * 여러 번 들어온 것을 접는다. 중복 제거를 Java에서 하면 OFFSET이 어긋난다 — 20건을 건너뛴
     * 뒤에 중복이 걸러지면 실제로는 20건보다 적게 넘어간 셈이 되어 페이지마다 항목이 겹친다.
     *
     * @param keywordPattern 대소문자 무시 정규식(예: {@code nickel|cobalt|lithium}).
     *                       {@code ~*}로 매칭하므로 호출자가 소문자로 만들 필요는 없다.
     */
    @Query(nativeQuery = true, value = """
            SELECT * FROM (
                SELECT DISTINCT ON (lower(trim(e.title))) e.*
                FROM raw_events e
                LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
                WHERE e.data_type = 'NEWS'
                  AND e.title IS NOT NULL
                  AND (:countryCode IS NULL OR e.country_code = :countryCode)
                  AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
                       OR an.material_category IS NOT NULL)
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
            String keywordPattern, String countryCode, int limit, int offset);

    /**
     * {@link #findSupplyChainNews} 조건에 맞는 전체 건수. 화면이 "다음 페이지가 있는지"가 아니라
     * "전부 몇 건인지"를 알아야 마지막 페이지에서 화살표를 정확히 잠글 수 있다.
     */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(DISTINCT lower(trim(e.title)))
            FROM raw_events e
            LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
            WHERE e.data_type = 'NEWS'
              AND e.title IS NOT NULL
              AND (:countryCode IS NULL OR e.country_code = :countryCode)
              AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
                   OR an.material_category IS NOT NULL)
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
    long countSupplyChainNews(String keywordPattern, String countryCode);

    /**
     * 최신 공급망 뉴스의 수집 시각(대시보드 "기준 시각"). {@link #findSupplyChainNews}가 보여주는
     * 목록 최상단 기사의 {@code collected_at}과 같은 값이다 — 그 목록은 {@code collected_at DESC}
     * 정렬이라 최댓값이 곧 맨 위 항목이다. 만료 규칙(심각 10일·주의 5일·그 외 3일)은 오래된 항목만
     * 걷어내고 최신 항목은 항상 창 안에 있으므로 MAX에 영향을 주지 않아 생략한다. 없으면 {@code null}.
     */
    @Query(nativeQuery = true, value = """
            SELECT MAX(e.collected_at)
            FROM raw_events e
            LEFT JOIN analyses an ON an.analysis_id = e.triggered_analysis_id
            WHERE e.data_type = 'NEWS'
              AND e.title IS NOT NULL
              AND ((coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
                   OR an.material_category IS NOT NULL)
            """)
    Instant findLatestSupplyChainCollectedAt(String keywordPattern);

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
