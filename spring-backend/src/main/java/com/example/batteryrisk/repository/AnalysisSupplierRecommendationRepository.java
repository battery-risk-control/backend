package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.AnalysisSupplierRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisSupplierRecommendationRepository extends JpaRepository<AnalysisSupplierRecommendation, Long> {
    List<AnalysisSupplierRecommendation> findByAnalysisIdOrderByRankPositionAsc(UUID analysisId);
}
