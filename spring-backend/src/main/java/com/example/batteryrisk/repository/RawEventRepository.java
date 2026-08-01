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
