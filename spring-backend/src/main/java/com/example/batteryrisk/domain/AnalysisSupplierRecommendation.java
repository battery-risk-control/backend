package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F3 분석 시 severity가 CRITICAL/WARNING이면 자동 계산되는 F9 대체 공급사 추천 1건(순위 1개)입니다. */
@Entity
@Table(name = "analysis_supplier_recommendations")
public class AnalysisSupplierRecommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "supplier_code", nullable = false, length = 50)
    private String supplierCode;

    @Column(name = "supplier_name", nullable = false, length = 150)
    private String supplierName;

    @Column(name = "rank_position", nullable = false)
    private int rankPosition;

    @Column(name = "pros")
    private String pros;

    @Column(name = "cons")
    private String cons;

    @Column(name = "recommendation_reason", length = 500)
    private String recommendationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisSupplierRecommendation() {}

    public static AnalysisSupplierRecommendation of(
            UUID analysisId, Long supplierId, String supplierCode, String supplierName,
            int rankPosition, List<String> pros, List<String> cons, String recommendationReason) {
        AnalysisSupplierRecommendation entity = new AnalysisSupplierRecommendation();
        entity.analysisId = analysisId;
        entity.supplierId = supplierId;
        entity.supplierCode = supplierCode;
        entity.supplierName = supplierName;
        entity.rankPosition = rankPosition;
        entity.pros = String.join("\n", pros);
        entity.cons = String.join("\n", cons);
        entity.recommendationReason = recommendationReason;
        entity.createdAt = Instant.now();
        return entity;
    }

    public Long getId() { return id; }
    public UUID getAnalysisId() { return analysisId; }
    public Long getSupplierId() { return supplierId; }
    public String getSupplierCode() { return supplierCode; }
    public String getSupplierName() { return supplierName; }
    public int getRankPosition() { return rankPosition; }
    public List<String> getPros() { return splitLines(pros); }
    public List<String> getCons() { return splitLines(cons); }
    public String getRecommendationReason() { return recommendationReason; }
    public Instant getCreatedAt() { return createdAt; }

    private List<String> splitLines(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("\n"));
    }
}
