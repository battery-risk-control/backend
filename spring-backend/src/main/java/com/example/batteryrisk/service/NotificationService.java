package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.NotificationLog;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F10: 메일 알림을 <b>최종 합성 위험도</b>(procurement_risk_assessments.procurement_risk_level)
 * 기준으로 보낸다. 합성 등급이 CRITICAL이면 즉시, WARNING이면 일일 다이제스트로 발송한다.
 *
 * <p>수신자는 {@code users} 테이블에서 역할로 조회한다(구매팀·경영기획팀·경영진). 제목 앞에는
 * 받는 사람의 역할 라벨을 붙여, 같은 알림이라도 "누구 앞으로 온 것인지"가 제목에서 바로 드러난다
 * ({@code [구매팀 알림]} / {@code [경영기획팀 알림]} / {@code [경영진 알림]}).
 *
 * <p>본문에는 세 가지를 함께 담는다: ① 뉴스 맥락(제목·국가·원문 링크, {@code analyses}에서 조회)
 * ② 합성/하위 점수(외부신호·ERP노출·계약공백) ③ AI 브리핑(본문·권장조치). 브리핑 출처는 경로마다
 * 다르다 — 즉시 CRITICAL은 멀티에이전트 {@code response}를 직접 받고(그 시점엔 ai_briefings 저장
 * 이전), 일일 다이제스트는 {@code ai_briefings}에서 읽는다.
 *
 * <p>자동(Chain A→B) 경로는 flush 타이밍 때문에 {@code analysis_id}를 null로 두고 {@code news_id}에
 * analysis UUID를 텍스트로 넣는다. 그래서 뉴스 조회는 {@code analysis_id ?? UUID(news_id)}로 푼다.
 *
 * <p>중복 발송은 {@code (assessment_id, channel, recipient)}로 막는다(V33).
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    // 알림 대상 역할. CRITICAL·일일 다이제스트 모두 이 세 역할(구매팀·경영기획팀·경영진)에게 보낸다.
    private static final List<Role> NOTIFY_ROLES = List.of(Role.PURCHASING, Role.STRATEGY, Role.EXECUTIVE);
    private static final String EMAIL_CHANNEL = "EMAIL";
    private static final String DIGEST_CHANNEL = "EMAIL_DIGEST";
    private static final int DIGEST_LOOKBACK_HOURS = 24;

    private final Map<String, NotificationChannel> channelsByName;
    private final UserRepository userRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final ProcurementRiskRepository procurementRiskRepository;
    private final AnalysisRepository analysisRepository;
    private final AiBriefingRepository aiBriefingRepository;

    public NotificationService(
            List<NotificationChannel> channels, UserRepository userRepository,
            NotificationLogRepository notificationLogRepository,
            ProcurementRiskRepository procurementRiskRepository,
            AnalysisRepository analysisRepository,
            AiBriefingRepository aiBriefingRepository) {
        this.channelsByName = channels.stream().collect(Collectors.toMap(NotificationChannel::name, Function.identity()));
        this.userRepository = userRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.procurementRiskRepository = procurementRiskRepository;
        this.analysisRepository = analysisRepository;
        this.aiBriefingRepository = aiBriefingRepository;
    }

    /** ai_briefings에 저장되기 전(즉시 CRITICAL) 브리핑은 DB에 없으므로 response 값으로 채운다. */
    public void notifyCritical(ProcurementRiskDto.Assessment assessment) {
        notifyCritical(assessment, null, null);
    }

    /**
     * 합성 등급이 CRITICAL인 평가 1건에 대한 즉시 알림. 호출부(오케스트레이션)가 CRITICAL만 넘긴다.
     *
     * @param briefingText        멀티에이전트 응답의 브리핑 본문. null이면 ai_briefings에서 조회한다.
     * @param recommendedActions  멀티에이전트 응답의 권장조치. null/빈 값이면 ai_briefings에서 조회한다.
     */
    public void notifyCritical(
            ProcurementRiskDto.Assessment assessment, String briefingText, List<String> recommendedActions) {
        NewsContext news = resolveNews(assessment);
        Briefing briefing = hasBriefingOverride(briefingText, recommendedActions)
                ? new Briefing(briefingText, recommendedActions)
                : resolveBriefing(assessment);

        String baseSubject = "[CRITICAL] 구매 리스크 급상승: " + materialLabel(assessment)
                + (news.title() != null ? " — " + news.title() : "");
        String body = buildBody(assessment, news, briefing);

        List<User> recipients = userRepository.findByRoleInAndEnabledTrueAndApprovalStatusAndEmailIsNotNull(
                NOTIFY_ROLES, ApprovalStatus.APPROVED);
        sendPerRecipient(assessment, EMAIL_CHANNEL, recipients, baseSubject, body);
    }

    /**
     * 수신자마다 역할 라벨을 붙여 발송한다. 같은 이메일이 여러 역할로 중복되면 한 번만 보낸다
     * (먼저 나온 역할 라벨을 쓴다). {@code (assessment_id, channel, recipient)}로 재발송을 막는다.
     */
    private void sendPerRecipient(
            ProcurementRiskDto.Assessment assessment, String channelName,
            List<User> recipients, String baseSubject, String body) {
        NotificationChannel channel = channelsByName.get(channelName);
        if (channel == null) return;

        Set<String> seenEmails = new LinkedHashSet<>();
        for (User user : recipients) {
            String recipient = user.getEmail();
            if (recipient == null || !seenEmails.add(recipient)) {
                continue;
            }
            if (notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(
                    assessment.assessmentId(), channelName, recipient)) {
                continue;
            }
            String subject = rolePrefix(user.getRole()) + baseSubject;
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
     * F10: 매일 08:00에 최근 24시간 내 <b>합성 등급 WARNING</b> 평가를 모아 구매팀·경영기획팀·경영진에게
     * 요약 이메일 1통으로 발송한다. 제목엔 각자의 역할 라벨이 붙는다. 자재별 최신 1건으로 접은 목록이라
     * 같은 뉴스의 중복 평가가 누적되지 않는다. 항목마다 뉴스 제목과 브리핑 권장조치 첫 줄을 함께 담는다.
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void sendDailyWarningDigest() {
        List<ProcurementRiskDto.Assessment> warnings = procurementRiskRepository.findRecentWarningAssessments(
                OffsetDateTime.now().minusHours(DIGEST_LOOKBACK_HOURS));
        if (warnings.isEmpty()) return;

        NotificationChannel email = channelsByName.get(EMAIL_CHANNEL);
        if (email == null) return;

        List<User> recipients = userRepository.findByRoleInAndEnabledTrueAndApprovalStatusAndEmailIsNotNull(
                NOTIFY_ROLES, ApprovalStatus.APPROVED);

        Set<String> seenEmails = new LinkedHashSet<>();
        for (User user : recipients) {
            String recipient = user.getEmail();
            if (recipient == null || !seenEmails.add(recipient)) {
                continue;
            }
            List<ProcurementRiskDto.Assessment> pending = warnings.stream()
                    .filter(a -> !notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(
                            a.assessmentId(), DIGEST_CHANNEL, recipient))
                    .toList();
            if (pending.isEmpty()) continue;

            String subject = rolePrefix(user.getRole())
                    + "[일일 브리핑] 주의(WARNING) 등급 구매 리스크 " + pending.size() + "건";
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
            NewsContext news = resolveNews(a);
            builder.append("- ").append(materialLabel(a));
            if (news.title() != null) {
                builder.append(" · ").append(news.title());
                if (news.country() != null) builder.append(" (").append(news.country()).append(")");
            }
            builder.append(" · 종합 ").append(plain(a.procurementRiskScore()))
                    .append(" (외부신호 ").append(plain(a.externalSignalScore()))
                    .append(" / ERP노출 ").append(plain(a.erpExposureScore()))
                    .append(" / 계약공백 ").append(plain(a.contractGapScore()))
                    .append(")\n");
            Briefing briefing = resolveBriefing(a);
            String firstAction = firstOrNull(briefing.recommendedActions());
            if (firstAction != null) {
                builder.append("    권장: ").append(firstAction).append("\n");
            }
        }
        return builder.toString();
    }

    private String buildBody(ProcurementRiskDto.Assessment a, NewsContext news, Briefing briefing) {
        StringBuilder builder = new StringBuilder()
                .append("자재: ").append(materialLabel(a)).append("\n");
        if (news.title() != null) {
            builder.append("사건: ").append(news.title());
            if (news.country() != null) builder.append(" (").append(news.country()).append(")");
            builder.append("\n");
        }
        if (news.url() != null) {
            builder.append("원문: ").append(news.url()).append("\n");
        }
        builder.append("종합 위험도: ").append(a.procurementRiskLevel())
                .append(" (점수 ").append(plain(a.procurementRiskScore())).append(")\n")
                .append("  · 외부신호(0.35): ").append(plain(a.externalSignalScore())).append("\n")
                .append("  · ERP 노출(0.45): ").append(plain(a.erpExposureScore())).append("\n")
                .append("  · 계약공백(0.20): ").append(plain(a.contractGapScore())).append("\n");
        if (briefing.text() != null && !briefing.text().isBlank()) {
            builder.append("\n[AI 브리핑]\n").append(briefing.text().strip()).append("\n");
        }
        if (briefing.recommendedActions() != null && !briefing.recommendedActions().isEmpty()) {
            builder.append("\n[권장 조치]\n");
            for (String action : briefing.recommendedActions()) {
                builder.append("- ").append(action).append("\n");
            }
        }
        if (a.riskReasons() != null && !a.riskReasons().isEmpty()) {
            builder.append("\n판단 근거: ").append(String.join("; ", a.riskReasons())).append("\n");
        }
        builder.append("assessment_id: ").append(a.assessmentId());
        if (a.analysisId() != null) {
            builder.append("\nanalysis_id: ").append(a.analysisId());
        }
        return builder.toString();
    }

    /** 받는 사람의 역할을 제목 접두사로. 실운영에서 같은 알림이 누구 앞으로 온 것인지 제목에서 드러낸다. */
    private static String rolePrefix(Role role) {
        if (role == null) {
            return "[알림] ";
        }
        return switch (role) {
            case PURCHASING -> "[구매팀 알림] ";
            case STRATEGY -> "[경영기획팀 알림] ";
            case EXECUTIVE -> "[경영진 알림] ";
            default -> "[알림] ";
        };
    }

    /** 뉴스 맥락 조회. 자동 경로는 analysis_id가 null이라 news_id의 UUID로 푼다. */
    private NewsContext resolveNews(ProcurementRiskDto.Assessment a) {
        UUID analysisId = a.analysisId() != null ? a.analysisId() : tryParseUuid(a.newsId());
        if (analysisId == null) {
            return NewsContext.EMPTY;
        }
        return analysisRepository.findById(analysisId)
                .map(an -> new NewsContext(an.getEventTitle(), an.getCountryCode(), an.getSourceUrl()))
                .orElse(NewsContext.EMPTY);
    }

    /** 저장된 브리핑(다이제스트 경로)에서 본문·권장조치를 가져온다. */
    private Briefing resolveBriefing(ProcurementRiskDto.Assessment a) {
        return aiBriefingRepository.findLatestSummaryByAssessmentId(a.assessmentId())
                .map(s -> new Briefing(s.briefingText(), s.recommendedActions()))
                .orElse(Briefing.EMPTY);
    }

    private static boolean hasBriefingOverride(String text, List<String> actions) {
        return (text != null && !text.isBlank()) || (actions != null && !actions.isEmpty());
    }

    private static UUID tryParseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String materialLabel(ProcurementRiskDto.Assessment a) {
        return a.materialCategory() != null ? a.materialCategory() : "미상 자재";
    }

    /** BigDecimal을 지수표기 없이 사람이 읽는 문자열로. null이면 "—". */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    /** 뉴스 맥락(제목·국가·원문 링크). 조회 실패/미연결이면 전부 null. */
    private record NewsContext(String title, String country, String url) {
        static final NewsContext EMPTY = new NewsContext(null, null, null);
    }

    /** 브리핑 본문·권장조치. 없으면 전부 null. */
    private record Briefing(String text, List<String> recommendedActions) {
        static final Briefing EMPTY = new Briefing(null, null);
    }
}
