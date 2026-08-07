package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    /** F10 중복 발송 방지 키(V33). 합성 평가 단위(assessment_id)로 이미 보냈는지 확인한다. */
    boolean existsByAssessmentIdAndChannelAndRecipient(UUID assessmentId, String channel, String recipient);

    List<NotificationLog> findByAnalysisIdAndChannel(UUID analysisId, String channel);
}
