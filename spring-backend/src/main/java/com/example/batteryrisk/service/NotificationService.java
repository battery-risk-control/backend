package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.NotificationLog;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.NotificationLogRepository;
import com.example.batteryrisk.repository.UserRepository;
import com.example.batteryrisk.service.notification.NotificationChannel;
import com.example.batteryrisk.service.notification.NotificationDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F10: severity가 CRITICAL이면 즉시, WARNING이면 일일 브리핑으로 알림을 보냅니다.
 * (analysis_id, channel, recipient) 조합이 notification_log에 이미 있으면 재발송하지 않습니다.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final List<Role> CRITICAL_RECIPIENT_ROLES = List.of(Role.PURCHASING, Role.STRATEGY, Role.EXECUTIVE);
    private static final List<Role> DIGEST_RECIPIENT_ROLES = List.of(Role.PURCHASING);
    private static final String DIGEST_CHANNEL = "EMAIL_DIGEST";
    private static final int DIGEST_LOOKBACK_HOURS = 24;

    private final Map<String, NotificationChannel> channelsByName;
    private final UserRepository userRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AnalysisRepository analysisRepository;

    public NotificationService(
            List<NotificationChannel> channels, UserRepository userRepository,
            NotificationLogRepository notificationLogRepository, AnalysisRepository analysisRepository) {
        this.channelsByName = channels.stream().collect(Collectors.toMap(NotificationChannel::name, Function.identity()));
        this.userRepository = userRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.analysisRepository = analysisRepository;
    }

    public void notifyCritical(Analysis analysis) {
        String subject = "[CRITICAL] 공급망 리스크 감지: " + analysis.getEventTitle();
        String body = buildBody(analysis);

        List<String> emailRecipients = userRepository
                .findByRoleInAndEnabledTrueAndEmailIsNotNull(CRITICAL_RECIPIENT_ROLES)
                .stream().map(User::getEmail).distinct().toList();

        sendViaChannel(analysis.getAnalysisId(), "EMAIL", emailRecipients, subject, body);
    }

    private void sendViaChannel(UUID analysisId, String channelName, List<String> recipients, String subject, String body) {
        NotificationChannel channel = channelsByName.get(channelName);
        if (channel == null) return;

        for (String recipient : recipients) {
            if (notificationLogRepository.existsByAnalysisIdAndChannelAndRecipient(analysisId, channelName, recipient)) {
                continue;
            }
            try {
                channel.send(recipient, subject, body);
                notificationLogRepository.save(NotificationLog.success(analysisId, channelName, recipient));
            } catch (NotificationDeliveryException exception) {
                log.warn("알림 발송 실패 (channel={}, recipient={}, analysisId={}): {}",
                        channelName, recipient, analysisId, exception.getMessage());
                notificationLogRepository.save(
                        NotificationLog.failure(analysisId, channelName, recipient, exception.getMessage()));
            }
        }
    }

    /** F10: 매일 08:00에 최근 24시간 내 WARNING 분석을 모아 구매팀에게 요약 이메일 1통으로 발송합니다. */
    @Scheduled(cron = "0 0 8 * * *")
    public void sendDailyWarningDigest() {
        List<Analysis> warnings = analysisRepository.findBySeverityAndCompletedAtGreaterThanEqual(
                "WARNING", Instant.now().minus(DIGEST_LOOKBACK_HOURS, ChronoUnit.HOURS));
        if (warnings.isEmpty()) return;

        NotificationChannel email = channelsByName.get("EMAIL");
        if (email == null) return;

        List<String> recipients = userRepository
                .findByRoleInAndEnabledTrueAndEmailIsNotNull(DIGEST_RECIPIENT_ROLES)
                .stream().map(User::getEmail).distinct().toList();

        for (String recipient : recipients) {
            List<Analysis> pending = warnings.stream()
                    .filter(a -> !notificationLogRepository.existsByAnalysisIdAndChannelAndRecipient(
                            a.getAnalysisId(), DIGEST_CHANNEL, recipient))
                    .toList();
            if (pending.isEmpty()) continue;

            String subject = "[일일 브리핑] WARNING 등급 리스크 " + pending.size() + "건";
            String body = buildDigestBody(pending);
            try {
                email.send(recipient, subject, body);
                pending.forEach(a -> notificationLogRepository.save(
                        NotificationLog.success(a.getAnalysisId(), DIGEST_CHANNEL, recipient)));
            } catch (NotificationDeliveryException exception) {
                log.warn("일일 브리핑 발송 실패 (recipient={}): {}", recipient, exception.getMessage());
                pending.forEach(a -> notificationLogRepository.save(
                        NotificationLog.failure(a.getAnalysisId(), DIGEST_CHANNEL, recipient, exception.getMessage())));
            }
        }
    }

    private String buildDigestBody(List<Analysis> analyses) {
        StringBuilder builder = new StringBuilder();
        for (Analysis analysis : analyses) {
            builder.append("- ").append(analysis.getEventTitle())
                    .append(" (").append(analysis.getCountryCode())
                    .append(", 점수 ").append(analysis.getSeverityScore()).append(")\n");
        }
        return builder.toString();
    }

    private String buildBody(Analysis analysis) {
        return "사건: " + analysis.getEventTitle() + "\n"
                + "국가: " + analysis.getCountryCode() + "\n"
                + "원문: " + analysis.getSourceUrl() + "\n"
                + "심각도: " + analysis.getSeverity() + " (점수 " + analysis.getSeverityScore() + ")\n"
                + "판단 근거: " + analysis.getReasonCodes() + "\n"
                + "analysis_id: " + analysis.getAnalysisId();
    }
}
