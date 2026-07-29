package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    boolean existsByAnalysisIdAndChannelAndRecipient(UUID analysisId, String channel, String recipient);

    List<NotificationLog> findByAnalysisIdAndChannel(UUID analysisId, String channel);
}
