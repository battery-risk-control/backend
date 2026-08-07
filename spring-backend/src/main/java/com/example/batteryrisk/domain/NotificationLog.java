package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * F10: CRITICAL 즉시 알림·WARNING 일일 브리핑 발송 이력.
 * (assessment_id, channel, recipient) 조합으로 중복 발송을 막습니다(V33). assessment_id는 합성
 * 위험도 평가마다 고유하고 NULL이 아니라, analysis_id로는 막지 못하던 두 경우(직접 입력 평가의
 * NULL analysis_id, 한 분석의 자재별 다중 평가)까지 자재 단위로 정확히 dedup됩니다.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id")
    private UUID assessmentId;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationLog() {}

    public static NotificationLog success(UUID assessmentId, UUID analysisId, String channel, String recipient) {
        NotificationLog log = new NotificationLog();
        log.assessmentId = assessmentId;
        log.analysisId = analysisId;
        log.channel = channel;
        log.recipient = recipient;
        log.status = "SENT";
        log.sentAt = Instant.now();
        return log;
    }

    public static NotificationLog failure(
            UUID assessmentId, UUID analysisId, String channel, String recipient, String errorMessage) {
        NotificationLog log = new NotificationLog();
        log.assessmentId = assessmentId;
        log.analysisId = analysisId;
        log.channel = channel;
        log.recipient = recipient;
        log.status = "FAILED";
        log.errorMessage = errorMessage;
        log.sentAt = Instant.now();
        return log;
    }

    public Long getId() { return id; }
    public UUID getAssessmentId() { return assessmentId; }
    public UUID getAnalysisId() { return analysisId; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getSentAt() { return sentAt; }
}
