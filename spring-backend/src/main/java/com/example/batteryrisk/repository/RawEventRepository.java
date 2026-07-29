package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.RawEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RawEventRepository extends JpaRepository<RawEvent, Long> {
    boolean existsBySourceAndExternalId(String source, String externalId);

    /**
     * 공개 뉴스 속보 패널용 최신 뉴스. title이 null인 행은 GDELT 커서 전진용 sentinel이라 제외한다
     * (GdeltRealtimeTriageAdapter 참고) — 화면에 빈 헤드라인이 뜨는 것을 막는다.
     */
    List<RawEvent> findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(String dataType, Pageable pageable);

    Optional<RawEvent> findFirstByDataTypeAndCountryCodeOrderByCollectedAtDesc(String dataType, String countryCode);

    Optional<RawEvent> findFirstByDataTypeOrderByCollectedAtDesc(String dataType);

    long countByDataTypeAndCollectedAtAfter(String dataType, Instant after);

    long countByDataTypeAndCountryCode(String dataType, String countryCode);

    List<RawEvent> findByDataTypeOrderByCollectedAtAsc(String dataType);

    List<RawEvent> findByDataTypeAndCountryCodeAndCollectedAtBetween(
            String dataType, String countryCode, Instant start, Instant end);
}
