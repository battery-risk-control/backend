package com.example.batteryrisk;

import com.example.batteryrisk.domain.NotificationLog;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.NotificationLogRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.UserRepository;
import com.example.batteryrisk.service.NotificationService;
import com.example.batteryrisk.service.notification.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F10: 메일 알림이 <b>최종 합성 등급</b>(procurement_risk_level) 기준으로 도는지 확인한다.
 *
 * <p>실제 SMTP 발송 대신 {@link NotificationChannel}을 모의해, "합성 CRITICAL → 즉시 발송 +
 * notification_log SENT", "합성 WARNING 다이제스트가 자재별 평가를 한 통으로 묶는지",
 * "이미 보낸 평가는 다시 안 보내는지(assessment_id dedup)"를 검증한다.
 */
class NotificationServiceTest {

    private NotificationChannel emailChannel;
    private UserRepository userRepository;
    private NotificationLogRepository notificationLogRepository;
    private ProcurementRiskRepository procurementRiskRepository;
    private AnalysisRepository analysisRepository;
    private AiBriefingRepository aiBriefingRepository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        emailChannel = mock(NotificationChannel.class);
        when(emailChannel.name()).thenReturn("EMAIL");
        userRepository = mock(UserRepository.class);
        notificationLogRepository = mock(NotificationLogRepository.class);
        procurementRiskRepository = mock(ProcurementRiskRepository.class);
        analysisRepository = mock(AnalysisRepository.class);
        aiBriefingRepository = mock(AiBriefingRepository.class);
        service = new NotificationService(
                List.of(emailChannel), userRepository, notificationLogRepository, procurementRiskRepository,
                analysisRepository, aiBriefingRepository);

        User buyer = mock(User.class);
        when(buyer.getEmail()).thenReturn("buyer@corp.com");
        when(userRepository.findByRoleInAndEnabledTrueAndEmailIsNotNull(anyList()))
                .thenReturn(List.of(buyer));
    }

    @Test
    void notifyCriticalSendsEmailAndLogsSentKeyedByAssessment() {
        ProcurementRiskDto.Assessment assessment = assessment("CRITICAL", UUID.randomUUID());
        when(notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(any(), any(), any()))
                .thenReturn(false);

        service.notifyCritical(assessment);

        verify(emailChannel).send(eq("buyer@corp.com"), any(), any());

        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(saved.capture());
        NotificationLog logEntry = saved.getValue();
        assertThat(logEntry.getStatus()).isEqualTo("SENT");
        assertThat(logEntry.getChannel()).isEqualTo("EMAIL");
        assertThat(logEntry.getAssessmentId()).isEqualTo(assessment.assessmentId());
        assertThat(logEntry.getRecipient()).isEqualTo("buyer@corp.com");
    }

    @Test
    void criticalSubjectCarriesRoleLabelPerRecipient() {
        User purchasing = mock(User.class);
        when(purchasing.getEmail()).thenReturn("p@corp.com");
        when(purchasing.getRole()).thenReturn(Role.PURCHASING);
        User strategy = mock(User.class);
        when(strategy.getEmail()).thenReturn("s@corp.com");
        when(strategy.getRole()).thenReturn(Role.STRATEGY);
        User executive = mock(User.class);
        when(executive.getEmail()).thenReturn("e@corp.com");
        when(executive.getRole()).thenReturn(Role.EXECUTIVE);
        when(userRepository.findByRoleInAndEnabledTrueAndEmailIsNotNull(anyList()))
                .thenReturn(List.of(purchasing, strategy, executive));
        when(notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(any(), any(), any()))
                .thenReturn(false);

        service.notifyCritical(assessment("CRITICAL", UUID.randomUUID()));

        assertThat(capturedSubjectFor("p@corp.com")).startsWith("[구매팀 알림] ");
        assertThat(capturedSubjectFor("s@corp.com")).startsWith("[경영기획팀 알림] ");
        assertThat(capturedSubjectFor("e@corp.com")).startsWith("[경영진 알림] ");
    }

    private String capturedSubjectFor(String recipient) {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailChannel).send(eq(recipient), subject.capture(), any());
        return subject.getValue();
    }

    @Test
    void notifyCriticalSkipsAlreadySentAssessment() {
        ProcurementRiskDto.Assessment assessment = assessment("CRITICAL", UUID.randomUUID());
        when(notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(any(), any(), any()))
                .thenReturn(true);

        service.notifyCritical(assessment);

        verify(emailChannel, never()).send(any(), any(), any());
        verify(notificationLogRepository, never()).save(any());
    }

    @Test
    void dailyDigestAggregatesWarningAssessmentsIntoOneEmail() {
        List<ProcurementRiskDto.Assessment> warnings = List.of(
                assessment("WARNING", UUID.randomUUID()),
                assessment("WARNING", UUID.randomUUID()));
        when(procurementRiskRepository.findRecentWarningAssessments(any())).thenReturn(warnings);
        when(notificationLogRepository.existsByAssessmentIdAndChannelAndRecipient(any(), any(), any()))
                .thenReturn(false);

        service.sendDailyWarningDigest();

        // 자재 2건이 한 통으로 나가야 한다.
        verify(emailChannel, times(1)).send(eq("buyer@corp.com"), any(), any());
        // 로그는 평가마다 한 줄씩(EMAIL_DIGEST) 남는다.
        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(entry -> {
                    assertThat(entry.getStatus()).isEqualTo("SENT");
                    assertThat(entry.getChannel()).isEqualTo("EMAIL_DIGEST");
                });
    }

    private static ProcurementRiskDto.Assessment assessment(String level, UUID analysisId) {
        return new ProcurementRiskDto.Assessment(
                UUID.randomUUID(),
                analysisId,
                "news-1",
                10L,
                "ERP-M-1",
                "ERP-S-1",
                "LITHIUM",
                "PRICE",
                OffsetDateTime.now(),
                new BigDecimal("82.0"),
                "CRITICAL",
                new BigDecimal("45.0"),
                new BigDecimal("30.0"),
                new BigDecimal("72.0"),
                level,
                List.of("리튬 최대 생산국 공급 차질"),
                Map.of(),
                Map.of(),
                "procurement-risk-v1",
                false,
                Boolean.TRUE,
                true,
                false,
                OffsetDateTime.now());
    }
}
