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

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

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

    /** LLM 추출이 만든 한국어 요약. 화면의 "뉴스 요약" 블록이 쓴다. */
    @Column(name = "summary_kr")
    private String summaryKr;

    @Column(name = "tone_score")
    private Double toneScore;

    @Column(name = "news_count")
    private Integer newsCount;

    @Column(name = "goldstein_scale")
    private Double goldsteinScale;

    protected Analysis() {}

    public static Analysis pending(
            Long materialId, Long supplierId, String eventTitle, String eventContent,
            String sourceName, String countryCode, String sourceUrl) {
        Analysis analysis = new Analysis();
        analysis.analysisId = UUID.randomUUID();
        analysis.materialId = materialId;
        analysis.supplierId = supplierId;
        analysis.eventTitle = eventTitle;
        analysis.eventContent = eventContent;
        analysis.sourceName = sourceName;
        analysis.countryCode = countryCode;
        analysis.sourceUrl = sourceUrl;
        analysis.status = "PENDING";
        analysis.createdAt = Instant.now();
        return analysis;
    }

    public void markProcessing() {
        status = "PROCESSING";
    }

    public void markCompleted(
            String impactDomain, Double confidence, String severity, double severityScore,
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

    /**
     * 점수를 만든 입력값(외부신호 상세)과 한국어 요약을 남깁니다.
     *
     * <p>{@link #markCompleted}와 나눠 둔 이유: 이쪽은 FastAPI 응답의 extraction·features 블록에서
     * 오는 값이라 경로에 따라 통째로 비어 있을 수 있고(추출 실패·구버전 응답), 그때 severity 저장까지
     * 함께 막히면 안 됩니다. 비면 null로 남고 화면이 해당 항목만 숨깁니다.
     */
    public void applySignalDetail(String summaryKr, Double toneScore, Integer newsCount, Double goldsteinScale) {
        this.summaryKr = summaryKr == null || summaryKr.isBlank() ? null : summaryKr.trim();
        this.toneScore = toneScore;
        this.newsCount = newsCount;
        this.goldsteinScale = goldsteinScale;
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
    public String getSourceUrl() { return sourceUrl; }
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
    public String getSummaryKr() { return summaryKr; }
    public Double getToneScore() { return toneScore; }
    public Integer getNewsCount() { return newsCount; }
    public Double getGoldsteinScale() { return goldsteinScale; }
}
