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

    /** 한국어로 번역된 제목. null이면 미번역이며 화면은 원문을 그대로 보여준다. */
    @Column(name = "title_ko", length = 500)
    private String titleKo;

    /** 번역 시도 횟수. 계속 실패하는 행이 매 주기 예산을 먹지 않도록 재시도를 제한한다. */
    @Column(name = "translation_attempts", nullable = false)
    private short translationAttempts;

    @Column(name = "content")
    private String content;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "goldstein_scale")
    private Double goldsteinScale;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "triggered_analysis_id")
    private UUID triggeredAnalysisId;

    @Column(name = "analysis_attempts", nullable = false)
    private short analysisAttempts;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected RawEvent() {}

    public static RawEvent of(
            String source, String dataType, String externalId, String contentHash,
            String title, String content, String sourceUrl, String countryCode,
            Double goldsteinScale, String payloadJson) {
        RawEvent event = new RawEvent();
        event.source = source;
        event.dataType = dataType;
        event.externalId = externalId;
        event.contentHash = contentHash;
        event.title = title;
        event.content = content;
        event.sourceUrl = sourceUrl;
        event.countryCode = countryCode;
        event.goldsteinScale = goldsteinScale;
        event.payloadJson = payloadJson;
        event.collectedAt = Instant.now();
        return event;
    }

    public void markTriggeredAnalysis(UUID analysisId) {
        this.triggeredAnalysisId = analysisId;
    }

    public void markAnalysisAttempt() { this.analysisAttempts++; }

    public void applyReplayCollectedAt(Instant replayedAt) {
        if (replayedAt != null) this.collectedAt = replayedAt;
    }

    /** 번역 성공. 빈 문자열은 저장하지 않는다 — 화면에 빈 헤드라인이 뜨느니 원문이 낫다. */
    public void applyTranslatedTitle(String translated) {
        if (translated != null && !translated.isBlank()) {
            this.titleKo = translated.trim();
        }
        this.translationAttempts++;
    }

    /** 번역 실패. 시도 횟수만 올려 다음 주기에 무한 재시도되지 않게 한다. */
    public void markTranslationFailed() {
        this.translationAttempts++;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getDataType() { return dataType; }
    public String getExternalId() { return externalId; }
    public String getContentHash() { return contentHash; }
    public String getTitle() { return title; }
    public String getTitleKo() { return titleKo; }
    public short getTranslationAttempts() { return translationAttempts; }
    public String getContent() { return content; }
    public String getSourceUrl() { return sourceUrl; }
    public String getCountryCode() { return countryCode; }
    public Double getGoldsteinScale() { return goldsteinScale; }
    public String getPayloadJson() { return payloadJson; }
    public UUID getTriggeredAnalysisId() { return triggeredAnalysisId; }
    public short getAnalysisAttempts() { return analysisAttempts; }
    public Instant getCollectedAt() { return collectedAt; }
}
