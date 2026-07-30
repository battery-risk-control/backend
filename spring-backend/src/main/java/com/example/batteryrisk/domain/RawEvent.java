package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "raw_events")
public class RawEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "triggered_analysis_id")
    private UUID triggeredAnalysisId;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected RawEvent() {}

    public static RawEvent of(
            String source, String dataType, String externalId, String contentHash,
            String title, String content, String sourceUrl, String countryCode, String payloadJson) {
        RawEvent event = new RawEvent();
        event.source = source;
        event.dataType = dataType;
        event.externalId = externalId;
        event.contentHash = contentHash;
        event.title = title;
        event.content = content;
        event.sourceUrl = sourceUrl;
        event.countryCode = countryCode;
        event.payloadJson = payloadJson;
        event.collectedAt = Instant.now();
        return event;
    }

    public void markTriggeredAnalysis(UUID analysisId) {
        this.triggeredAnalysisId = analysisId;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getDataType() { return dataType; }
    public String getExternalId() { return externalId; }
    public String getContentHash() { return contentHash; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getSourceUrl() { return sourceUrl; }
    public String getCountryCode() { return countryCode; }
    public String getPayloadJson() { return payloadJson; }
    public UUID getTriggeredAnalysisId() { return triggeredAnalysisId; }
    public Instant getCollectedAt() { return collectedAt; }
}
