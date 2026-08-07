package com.example.batteryrisk.config;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 프론트엔드 e2e(Playwright)가 의존하는 고정 테스트 계정을 users 테이블에 심는다.
 *
 * <p>운영 환경에 알려진 약한 비밀번호 계정이 새는 것을 막기 위해 항상 도는 Flyway seed가 아니라
 * {@code app.auth.test-seed.enabled=true}일 때만 실행되는 조건부 Runner로 둔다(ErpSeedConfig와 동일한 방식).
 * CI/로컬 e2e에서만 {@code AUTH_TEST_SEED_ENABLED=true}로 켠다.
 *
 * <p>계정 스펙은 프론트엔드 {@code src/api/auth.api.ts}의 TEST_ACCOUNTS 및 e2e 스펙과 1:1로 맞춰져 있다.
 * (org_tier planning ↔ 백엔드 Role.STRATEGY 변환은 {@link Role} 참고)
 */
@Configuration
@ConditionalOnProperty(name = "app.auth.test-seed.enabled", havingValue = "true")
public class AuthTestSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(AuthTestSeedConfig.class);

    /** 프론트엔드 회원가입 폼이 소속 회사명을 고정으로 보내는 값과 동일하게 맞춘다. */
    private static final String DEFAULT_ORG_NAME = "OO배터리";

    /**
     * 승인 완료 계정(구매·기획·임원)의 email 컬럼에 넣을 값. 비어 있으면 각 계정의 기본 @test.local을 쓴다.
     * F10 메일 검증 편의용 — {@code NOTIFICATION_MAIL_FROM}(실제 받은편지함)을 그대로 물려, psql 없이
     * 재기동만으로 CRITICAL/다이제스트 수신자가 실제 주소로 바뀐다. 로그인 아이디(username)는 그대로라
     * 로그인엔 영향이 없고, distinct 발송이라 세 계정이 같은 주소여도 메일은 한 통만 나간다.
     */
    @Value("${app.auth.test-seed.recipient-email:}")
    private String recipientEmail;

    private static final String UPSERT_SQL = """
            INSERT INTO users (
                username, password, name, email, org_name, approval_status, role,
                enabled, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, now(), now())
            ON CONFLICT (username) DO UPDATE SET
                password = EXCLUDED.password,
                name = EXCLUDED.name,
                email = EXCLUDED.email,
                org_name = EXCLUDED.org_name,
                approval_status = EXCLUDED.approval_status,
                role = EXCLUDED.role,
                enabled = TRUE,
                updated_at = now()
            """;

    @Bean
    ApplicationRunner authTestSeedRunner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return args -> {
            String approvedHash = passwordEncoder.encode("test1234!");
            List<SeedAccount> accounts = List.of(
                    // e2e auth-login/tier-access가 각 org_tier 대시보드 진입을 검증하는 승인 완료 계정 3종.
                    // F10 알림 대상은 이 셋(구매팀·경영기획팀·경영진)이다. recipient-email이 있으면 email을
                    // 실제로 받을 수 있는 주소로 덮는다 — users.email 유니크 제약(uk_users_email) 때문에
                    // 셋을 같은 주소로 둘 수 없으므로, 기획·임원은 Gmail 플러스 주소(kjminji01+planning@ 등)로
                    // 둔다. 그러면 셋 다 같은 받은편지함으로 오되 주소가 달라 제약을 통과하고, 제목의 역할
                    // 라벨로 구분된다. username은 그대로라 로그인엔 영향이 없다.
                    new SeedAccount("purchasing@test.local", emailFor("purchasing@test.local"), approvedHash,
                            "구매팀 테스트", Role.PURCHASING, ApprovalStatus.APPROVED),
                    new SeedAccount("planning@test.local", emailWithTagOr("planning", "planning@test.local"), approvedHash,
                            "경영기획팀 테스트", Role.STRATEGY, ApprovalStatus.APPROVED),
                    new SeedAccount("executive@test.local", emailWithTagOr("executive", "executive@test.local"), approvedHash,
                            "임원 테스트", Role.EXECUTIVE, ApprovalStatus.APPROVED),
                    // pending-approval.spec가 승인 대기 락 화면을 검증하는 계정. email은 NULL로 둔다 —
                    // 로그인은 username 기반이라 영향이 없고, 이렇게 해야 알림 수신 대상(email IS NOT NULL)에서
                    // 빠져 가짜 주소로 발송/바운스되지 않는다. 비밀번호는 'anything'으로 맞춰야
                    // 로그인(비밀번호 검증 → 승인상태 확인) 순서상 403(PENDING_APPROVAL)까지 도달한다.
                    new SeedAccount("pending@company.com", null, passwordEncoder.encode("anything"),
                            "승인대기 테스트", Role.PURCHASING, ApprovalStatus.PENDING));

            for (SeedAccount account : accounts) {
                jdbc.update(UPSERT_SQL,
                        account.username(), account.passwordHash(), account.name(),
                        account.email(), DEFAULT_ORG_NAME,
                        account.approvalStatus().name(), account.role().name());
            }
            log.info("Auth test-seed completed: {} accounts upserted (recipient-email override: {})",
                    accounts.size(), recipientEmail == null || recipientEmail.isBlank() ? "none" : recipientEmail);
        };
    }

    /** recipient-email이 설정돼 있으면 그 주소를, 아니면 계정 기본 @test.local을 쓴다. */
    private String emailFor(String localAddress) {
        return recipientEmail == null || recipientEmail.isBlank() ? localAddress : recipientEmail.trim();
    }

    /**
     * recipient-email에 플러스 태그를 끼운 주소({@code local+tag@domain})를, 미설정/형식오류면 폴백을 쓴다.
     * 같은 받은편지함으로 배달되면서도 주소가 달라 uk_users_email 유니크 제약을 피한다(Gmail 플러스 주소).
     */
    private String emailWithTagOr(String tag, String fallback) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return fallback;
        }
        String base = recipientEmail.trim();
        int at = base.indexOf('@');
        if (at <= 0) {
            return fallback;
        }
        return base.substring(0, at) + "+" + tag + base.substring(at);
    }

    private record SeedAccount(
            String username, String email, String passwordHash, String name,
            Role role, ApprovalStatus approvalStatus) {}
}
