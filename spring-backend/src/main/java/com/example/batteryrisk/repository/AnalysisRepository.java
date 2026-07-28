package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.Analysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
    /** F10: 일일 브리핑 대상 WARNING 분석 목록입니다. */
    List<Analysis> findBySeverityAndCompletedAtGreaterThanEqual(String severity, Instant since);
}
