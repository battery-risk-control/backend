package com.example.batteryrisk;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정 잠금(연속 5회 실패) 검증. 캡챠 게이트가 실패 누적을 막지 않도록 임계값을 높여
 * 잠금 경로만 격리해서 본다(실제 운영은 캡챠 임계 3 < 잠금 5로, 캡챠를 푼 뒤에도 실패하면 잠긴다).
 */
@SpringBootTest(properties = {
        "app.auth.captcha-threshold=99",
        // 별도 프로퍼티라 새 컨텍스트가 뜬다. 기본 컨텍스트와 같은 인메모리 DB를 공유하면
        // create-drop이 서로의 스키마를 건드릴 수 있어 전용 DB 이름으로 격리한다.
        "spring.datasource.url=jdbc:h2:mem:battery_risk_lockout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AuthLockoutTest {
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void fiveFailedLoginsLockTheAccount() throws Exception {
        userRepository.save(new User(
                "lockuser", passwordEncoder.encode("Abcd1234!"), "잠금-이름", Role.PURCHASING,
                "lock@company.com", "OO배터리", ApprovalStatus.APPROVED));

        String wrong = """
                {"email":"lock@company.com","password":"Wrong9999!"}""";

        // 1~4회: 자격증명 오류.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }
        // 5회째: 상한 도달 → 잠금(423).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));

        // 잠금 중에는 올바른 비밀번호로도 로그인이 막힌다.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"lock@company.com","password":"Abcd1234!"}"""))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }
}
