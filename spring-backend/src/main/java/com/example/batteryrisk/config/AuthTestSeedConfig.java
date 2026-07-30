package com.example.batteryrisk.config;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                    // e2e auth-login/tier-access가 각 org_tier 대시보드 진입을 검증하는 승인 완료 계정 3종
                    new SeedAccount("purchasing@test.local", approvedHash, "구매팀 테스트",
                            Role.PURCHASING, ApprovalStatus.APPROVED),
                    new SeedAccount("planning@test.local", approvedHash, "경영기획팀 테스트",
                            Role.STRATEGY, ApprovalStatus.APPROVED),
                    new SeedAccount("executive@test.local", approvedHash, "임원 테스트",
                            Role.EXECUTIVE, ApprovalStatus.APPROVED),
                    // pending-approval.spec가 승인 대기 락 화면을 검증하는 계정.
                    // e2e가 password 'anything'으로 로그인을 시도하고, 로그인은 비밀번호 검증 → 승인상태 확인
                    // 순서이므로 비밀번호를 'anything'으로 맞춰야 403(PENDING_APPROVAL)까지 도달한다.
                    new SeedAccount("pending@company.com", passwordEncoder.encode("anything"),
                            "승인대기 테스트", Role.PURCHASING, ApprovalStatus.PENDING));

            for (SeedAccount account : accounts) {
                jdbc.update(UPSERT_SQL,
                        account.email(), account.passwordHash(), account.name(),
                        account.email(), DEFAULT_ORG_NAME,
                        account.approvalStatus().name(), account.role().name());
            }
            log.info("Auth test-seed completed: {} accounts upserted (username = email)", accounts.size());
        };
    }

    private record SeedAccount(
            String email, String passwordHash, String name, Role role, ApprovalStatus approvalStatus) {}
}
