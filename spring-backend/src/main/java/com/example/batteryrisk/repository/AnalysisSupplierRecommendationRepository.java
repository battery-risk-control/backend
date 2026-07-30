package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.AnalysisSupplierRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AnalysisSupplierRecommendationRepository extends JpaRepository<AnalysisSupplierRecommendation, Long> {
    List<AnalysisSupplierRecommendation> findByAnalysisIdOrderByRankPositionAsc(UUID analysisId);

    /**
     * 공개 권고 리스트에서 분석 여러 건의 대체 후보 수를 한 번에 세기 위한 조회다.
     * 분석당 1회씩 조회하면 N+1이 되므로 IN 절로 묶는다.
     */
    List<AnalysisSupplierRecommendation> findByAnalysisIdIn(Collection<UUID> analysisIds);
}
