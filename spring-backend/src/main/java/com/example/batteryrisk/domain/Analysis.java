package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "analyses")
public class Analysis {
    @Id
    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "event_title", nullable = false, length = 500)
    private String eventTitle;

    @Column(name = "event_content", nullable = false)
    private String eventContent;

    @Column(name = "source_name", length = 200)
    private String sourceName;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "impact_domain", length = 50)
    private String impactDomain;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "severity_score")
    private Double severityScore;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "reason_codes")
    private String reasonCodes;

    @Column(name = "rule_version", length = 100)
    private String ruleVersion;

    @Column(name = "mock", nullable = false)
    private boolean mock;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "material_category", length = 50)
    private String materialCategory;

    @Column(name = "recommendation_caveats")
    private String recommendationCaveats;

    protected Analysis() {}

    public static Analysis pending(
            Long materialId, Long supplierId, String eventTitle, String eventContent,
            String sourceName, String countryCode) {
        Analysis analysis = new Analysis();
        analysis.analysisId = UUID.randomUUID();
        analysis.materialId = materialId;
        analysis.supplierId = supplierId;
        analysis.eventTitle = eventTitle;
        analysis.eventContent = eventContent;
        analysis.sourceName = sourceName;
        analysis.countryCode = countryCode;
        analysis.status = "PENDING";
        analysis.createdAt = Instant.now();
        return analysis;
    }

    public void markProcessing() {
        status = "PROCESSING";
    }

    public void markCompleted(
            String impactDomain, double confidence, String severity, double severityScore,
            String reasonCodes, String ruleVersion, boolean mock) {
        status = "COMPLETED";
        this.impactDomain = impactDomain;
        this.confidence = confidence;
        this.severity = severity;
        this.severityScore = severityScore;
        this.reasonCodes = reasonCodes;
        this.ruleVersion = ruleVersion;
        this.mock = mock;
        errorCode = null;
        errorMessage = null;
        completedAt = Instant.now();
    }

    /** severity가 CRITICAL/WARNING이라 F9 대체 공급사 추천이 자동 계산됐을 때만 호출됩니다. */
    public void attachSupplierRecommendation(String materialCategory, List<String> caveats) {
        this.materialCategory = materialCategory;
        this.recommendationCaveats = String.join("\n", caveats);
    }

    public void markFailed(String errorCode, String errorMessage) {
        status = "FAILED";
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        completedAt = Instant.now();
    }

    public UUID getAnalysisId() { return analysisId; }
    public Long getMaterialId() { return materialId; }
    public Long getSupplierId() { return supplierId; }
    public String getEventTitle() { return eventTitle; }
    public String getEventContent() { return eventContent; }
    public String getSourceName() { return sourceName; }
    public String getCountryCode() { return countryCode; }
    public String getStatus() { return status; }
    public String getImpactDomain() { return impactDomain; }
    public String getSeverity() { return severity; }
    public Double getSeverityScore() { return severityScore; }
    public Double getConfidence() { return confidence; }
    public String getReasonCodes() { return reasonCodes; }
    public String getRuleVersion() { return ruleVersion; }
    public boolean isMock() { return mock; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getMaterialCategory() { return materialCategory; }
    public String getRecommendationCaveats() { return recommendationCaveats; }
}
