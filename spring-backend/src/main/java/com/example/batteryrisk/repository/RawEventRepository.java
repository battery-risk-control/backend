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

    /** 위와 같되 국가로 좁힌다. 지도 마커를 클릭했을 때 그 국가 뉴스만 보여주는 경로에서 쓴다. */
    List<RawEvent> findByDataTypeAndCountryCodeAndTitleIsNotNullOrderByCollectedAtDesc(
            String dataType, String countryCode, Pageable pageable);

    /**
     * 자재 키워드가 제목이나 본문에 있는 뉴스만, 제목 중복을 제거해 최신순으로.
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
                WHERE e.data_type = 'NEWS'
                  AND e.title IS NOT NULL
                  AND (:countryCode IS NULL OR e.country_code = :countryCode)
                  AND (coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
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
            WHERE e.data_type = 'NEWS'
              AND e.title IS NOT NULL
              AND (:countryCode IS NULL OR e.country_code = :countryCode)
              AND (coalesce(e.title, '') || ' ' || coalesce(e.content, '')) ~* :keywordPattern
            """)
    long countSupplyChainNews(String keywordPattern, String countryCode);

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

    Optional<RawEvent> findFirstByDataTypeAndCountryCodeOrderByCollectedAtDesc(String dataType, String countryCode);

    Optional<RawEvent> findFirstByDataTypeOrderByCollectedAtDesc(String dataType);

    long countByDataTypeAndCollectedAtAfter(String dataType, Instant after);

    long countByDataTypeAndCountryCode(String dataType, String countryCode);

    List<RawEvent> findByDataTypeOrderByCollectedAtAsc(String dataType);

    List<RawEvent> findByDataTypeAndCountryCodeAndCollectedAtBetween(
            String dataType, String countryCode, Instant start, Instant end);
}
