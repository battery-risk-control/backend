package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.RawEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RawEventRepository extends JpaRepository<RawEvent, Long> {
    boolean existsBySourceAndExternalId(String source, String externalId);

    Optional<RawEvent> findFirstByDataTypeAndCountryCodeOrderByCollectedAtDesc(String dataType, String countryCode);

    Optional<RawEvent> findFirstByDataTypeOrderByCollectedAtDesc(String dataType);

    long countByDataTypeAndCollectedAtAfter(String dataType, Instant after);

    long countByDataTypeAndCountryCode(String dataType, String countryCode);

    List<RawEvent> findByDataTypeOrderByCollectedAtAsc(String dataType);

    List<RawEvent> findByDataTypeAndCountryCodeAndCollectedAtBetween(
            String dataType, String countryCode, Instant start, Instant end);
}
