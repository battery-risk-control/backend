package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.NotificationLog;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.repository.NotificationLogRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.UserRepository;
import com.example.batteryrisk.service.notification.NotificationChannel;
import com.example.batteryrisk.service.notification.NotificationDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F10: 메일 알림을 <b>최종 합성 위험도</b>(procurement_risk_assessments.procurement_risk_level)
 * 기준으로 보낸다. 합성 등급이 CRITICAL이면 즉시, WARNING이면 일일 다이제스트로 발송한다.
 *
 * <p>예전에는 외부 뉴스 severity(analyses.severity)만 보고 트리거했다. 지금은 외부신호 0.35 +
 * ERP 노출 0.45 + 계약공백 0.20을 합친 최종 등급이 있으므로, 그 등급으로 갈아끼웠다. 즉시 트리거는
 * 합성 평가가 저장되는 {@link MultiAgentOrchestrationService}에서 걸린다 — 합성 등급은 멀티에이전트가
 * 돈 뒤에만 존재하기 때문이다(KG 조기 종료 등으로 합성이 안 나온 건은 메일도 안 나가는 게 맞다).
 *
 * <p>중복 발송은 {@code (assessment_id, channel, recipient)}로 막는다(V33). assessment_id는 자재별로
 * 고유하고 NULL이 아니라, 한 분석이 자재 여럿으로 펼쳐져도 자재마다 정확히 한 통씩 나간다.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final List<Role> CRITICAL_RECIPIENT_ROLES = List.of(Role.PURCHASING, Role.STRATEGY, Role.EXECUTIVE);
    private static final List<Role> DIGEST_RECIPIENT_ROLES = List.of(Role.PURCHASING);
    private static final String EMAIL_CHANNEL = "EMAIL";
    private static final String DIGEST_CHANNEL = "EMAIL_DIGEST";
    private static final int DIGEST_LOOKBACK_HOURS = 24;

    private final Map<String, NotificationChannel> channelsByName;
    private final UserRepository userRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final ProcurementRiskRepository procurementRiskRepository;

    public NotificationService(
            List<NotificationChannel> channels, UserRepository userRepository,
            NotificationLogRepository notificationLogRepository,
            ProcurementRiskRepository procurementRiskRepository) {
        this.channelsByName = channels.stream().collect(Collectors.toMap(NotificationChannel::name, Function.identity()));
        this.userRepository = userRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.procurementRiskRepository = procurementRiskRepository;
    }

    /** 합성 등급이 CRITICAL인 평가 1건에 대한 즉시 알림. 호출부(오케스트레이션)가 CRITICAL만 넘긴다. */
    public void notifyCritical(ProcurementRiskDto.Assessment assessment) {
        String subject = "[CRITICAL] 구매 리스크 급상승: " + materialLabel(assessment);
        String body = buildBody(assessment);

        List<String> emailRecipients = userRepository
                .findByRoleInAndEnabledTrueAndEmailIsNotNull(CRITICAL_RECIPIENT_ROLES)
                .stream().map(User::getEmail).distinct().toList();

        sendViaChannel(assessment, EMAIL_CHANNEL, emailRecipients, subject, body);
    }

    private void sendViaChannel(
            ProcurementRiskDto.Assessment assessment, String channelName,
            List<String> recipients, String subject, String body) {
        NotificationChannel channel = channelsByName.get(channelName);
        if (channel == null) return;

        for (String recipient : recipients) {
            if (notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(
                    assessment.assessmentId(), channelName, recipient)) {
                continue;
            }
            try {
                channel.send(recipient, subject, body);
                notificationLogRepository.save(NotificationLog.success(
                        assessment.assessmentId(), assessment.analysisId(), channelName, recipient));
            } catch (NotificationDeliveryException exception) {
                log.warn("알림 발송 실패 (channel={}, recipient={}, assessmentId={}): {}",
                        channelName, recipient, assessment.assessmentId(), exception.getMessage());
                notificationLogRepository.save(NotificationLog.failure(
                        assessment.assessmentId(), assessment.analysisId(), channelName, recipient,
                        exception.getMessage()));
            }
        }
    }

    /**
     * F10: 매일 08:00에 최근 24시간 내 <b>합성 등급 WARNING</b> 평가를 모아 구매팀에게 요약 이메일
     * 1통으로 발송한다. 자재별 최신 1건으로 접은 목록이라 같은 뉴스의 중복 평가가 누적되지 않는다.
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void sendDailyWarningDigest() {
        List<ProcurementRiskDto.Assessment> warnings = procurementRiskRepository.findRecentWarningAssessments(
                OffsetDateTime.now().minusHours(DIGEST_LOOKBACK_HOURS));
        if (warnings.isEmpty()) return;

        NotificationChannel email = channelsByName.get(EMAIL_CHANNEL);
        if (email == null) return;

        List<String> recipients = userRepository
                .findByRoleInAndEnabledTrueAndEmailIsNotNull(DIGEST_RECIPIENT_ROLES)
                .stream().map(User::getEmail).distinct().toList();

        for (String recipient : recipients) {
            List<ProcurementRiskDto.Assessment> pending = warnings.stream()
                    .filter(a -> !notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(
                            a.assessmentId(), DIGEST_CHANNEL, recipient))
                    .toList();
            if (pending.isEmpty()) continue;

            String subject = "[일일 브리핑] 주의(WARNING) 등급 구매 리스크 " + pending.size() + "건";
            String body = buildDigestBody(pending);
            try {
                email.send(recipient, subject, body);
                pending.forEach(a -> notificationLogRepository.save(
                        NotificationLog.success(a.assessmentId(), a.analysisId(), DIGEST_CHANNEL, recipient)));
            } catch (NotificationDeliveryException exception) {
                log.warn("일일 브리핑 발송 실패 (recipient={}): {}", recipient, exception.getMessage());
                pending.forEach(a -> notificationLogRepository.save(
                        NotificationLog.failure(a.assessmentId(), a.analysisId(), DIGEST_CHANNEL, recipient,
                                exception.getMessage())));
            }
        }
    }

    private String buildDigestBody(List<ProcurementRiskDto.Assessment> assessments) {
        StringBuilder builder = new StringBuilder();
        for (ProcurementRiskDto.Assessment a : assessments) {
            builder.append("- ").append(materialLabel(a))
                    .append(" · 종합 ").append(plain(a.procurementRiskScore()))
                    .append(" (외부신호 ").append(plain(a.externalSignalScore()))
                    .append(" / ERP노출 ").append(plain(a.erpExposureScore()))
                    .append(" / 계약공백 ").append(plain(a.contractGapScore()))
                    .append(")\n");
        }
        return builder.toString();
    }

    private String buildBody(ProcurementRiskDto.Assessment a) {
        StringBuilder builder = new StringBuilder()
                .append("자재: ").append(materialLabel(a)).append("\n")
                .append("종합 위험도: ").append(a.procurementRiskLevel())
                .append(" (점수 ").append(plain(a.procurementRiskScore())).append(")\n")
                .append("  · 외부신호(0.35): ").append(plain(a.externalSignalScore())).append("\n")
                .append("  · ERP 노출(0.45): ").append(plain(a.erpExposureScore())).append("\n")
                .append("  · 계약공백(0.20): ").append(plain(a.contractGapScore())).append("\n");
        if (a.riskReasons() != null && !a.riskReasons().isEmpty()) {
            builder.append("판단 근거: ").append(String.join("; ", a.riskReasons())).append("\n");
        }
        builder.append("assessment_id: ").append(a.assessmentId());
        if (a.analysisId() != null) {
            builder.append("\nanalysis_id: ").append(a.analysisId());
        }
        return builder.toString();
    }

    private static String materialLabel(ProcurementRiskDto.Assessment a) {
        return a.materialCategory() != null ? a.materialCategory() : "미상 자재";
    }

    /** BigDecimal을 지수표기 없이 사람이 읽는 문자열로. null이면 "—". */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
