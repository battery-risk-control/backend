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

/**
 * 운영용 관리자(ADMIN) 계정 시드. 규제 가이드 시큐어코딩 5(관리자 권한 접속) 대응.
 *
 * <p>관리자 자격증명이 Flyway 마이그레이션에 박제되면 저장소에 약한/고정 비밀번호가 남으므로,
 * env로 주입할 때만({@code app.auth.admin-seed.enabled=true}) 실행되는 조건부 Runner로 둔다
 * (AuthTestSeedConfig와 동일한 방식). username/password가 비어 있으면 아무것도 하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.auth.admin-seed.enabled", havingValue = "true")
public class AdminSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(AdminSeedConfig.class);

    @Value("${app.auth.admin-seed.username:}")
    private String adminUsername;

    @Value("${app.auth.admin-seed.password:}")
    private String adminPassword;

    @Value("${app.auth.admin-seed.name:시스템 관리자}")
    private String adminName;

    @Value("${app.auth.admin-seed.email:}")
    private String adminEmail;

    private static final String UPSERT_SQL = """
            INSERT INTO users (
                username, password, name, email, approval_status, role,
                enabled, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'APPROVED', ?, TRUE, now(), now())
            ON CONFLICT (username) DO UPDATE SET
                password = EXCLUDED.password,
                name = EXCLUDED.name,
                email = EXCLUDED.email,
                approval_status = 'APPROVED',
                role = EXCLUDED.role,
                enabled = TRUE,
                updated_at = now()
            """;

    @Bean
    ApplicationRunner adminSeedRunner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return args -> {
            if (adminUsername == null || adminUsername.isBlank()
                    || adminPassword == null || adminPassword.isBlank()) {
                log.warn("Admin seed enabled but username/password missing — skipped.");
                return;
            }
            jdbc.update(UPSERT_SQL,
                    adminUsername.trim(),
                    passwordEncoder.encode(adminPassword),
                    adminName,
                    adminEmail == null || adminEmail.isBlank() ? null : adminEmail.trim(),
                    Role.ADMIN.name());
            log.info("Admin seed completed: username={}, status={}", adminUsername.trim(), ApprovalStatus.APPROVED);
        };
    }
}
