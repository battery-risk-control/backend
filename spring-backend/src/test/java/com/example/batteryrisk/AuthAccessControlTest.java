package com.example.batteryrisk;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.repository.UserConsentRepository;
import com.example.batteryrisk.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 규제 가이드 대응(회원가입 동의·비밀번호 규칙·마스킹·접근통제·관리자 인가) 검증.
 * 계정 잠금(누적 5회)은 캡챠 게이트와 상호작용하므로 별도 {@link AuthLockoutTest}에서 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAccessControlTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired UserConsentRepository userConsentRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private static String frontendSignup(String name, String email, String password,
                                         boolean privacy, boolean marketing) {
        return """
                {"name":"%s","email":"%s","password":"%s","org_tier":"purchasing","org_name":"OO배터리",
                 "privacy_required_consent":%s,"marketing_optional_consent":%s}
                """.formatted(name, email, password, privacy, marketing);
    }

    // ── ② 비밀번호 규칙 ────────────────────────────────────────────────

    @Test
    void weakPasswordIsRejectedOnSignup() throws Exception {
        // 영문만 7자 → 1종류라 정책 위반. @ValidPassword가 400으로 막는다.
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("김약함", "weak@company.com", "abcdefg", true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void twoKindPasswordNeedsTenChars() throws Exception {
        // 영문+숫자 2종류 9자 → 10자 미만이라 위반.
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("김구자", "twokind@company.com", "abcd12345", true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ── ① 개인정보 수집·이용 동의 ────────────────────────────────────

    @Test
    void signupWithoutRequiredConsentIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("무동의", "noconsent@company.com", "Abcd1234!", false, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void signupWithConsentSucceedsAndStoresConsentRows() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("동의함", "consent@company.com", "Abcd1234!", true, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        User user = userRepository.findByEmail("consent@company.com").orElseThrow();
        assertEquals(2, userConsentRepository.findByUserId(user.getId()).size());
    }

    // ── ⑧ 이메일 형식·중복 ────────────────────────────────────────────

    @Test
    void invalidEmailFormatIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("형식오류", "not-an-email", "Abcd1234!", true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String body = frontendSignup("첫가입", "dup@company.com", "Abcd1234!", true, false);
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
    }

    // ── ⑥ 비밀번호 유효기간 만료 → 재설정 ───────────────────────────

    @Test
    void expiredPasswordBlocksLoginThenResetRestoresIt() throws Exception {
        User user = saveApprovedUser("expireuser", "expire@company.com", "Abcd1234!", Role.PURCHASING);
        jdbc.update("UPDATE users SET password_changed_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(91), user.getId());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"expire@company.com","password":"Abcd1234!"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_EXPIRED"));

        mockMvc.perform(post("/api/v1/auth/password/reset-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"expire@company.com","current_password":"Abcd1234!","new_password":"Zxcv9876!"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"expire@company.com","password":"Zxcv9876!"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists());
    }

    @Test
    void resetExpiredRejectsReusedPassword() throws Exception {
        User user = saveApprovedUser("reuseuser", "reuse@company.com", "Abcd1234!", Role.PURCHASING);
        jdbc.update("UPDATE users SET password_changed_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(91), user.getId());

        mockMvc.perform(post("/api/v1/auth/password/reset-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reuse@company.com","current_password":"Abcd1234!","new_password":"Abcd1234!"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_REUSED"));
    }

    // ── ⑥ 캡챠(누적 실패 후 요구) ────────────────────────────────────

    @Test
    void captchaIsRequiredAfterThreeFailures() throws Exception {
        saveApprovedUser("capuser", "cap@company.com", "Abcd1234!", Role.PURCHASING);
        String wrong = """
                {"email":"cap@company.com","password":"Wrong9999!"}""";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }
        // 4번째 시도: 누적 3회이므로 비밀번호 검증 전에 캡챠를 요구한다(428).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.error.code").value("CAPTCHA_REQUIRED"));

        // 캡챠 발급 엔드포인트는 비인증으로 접근 가능해야 한다.
        mockMvc.perform(get("/api/v1/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captcha_id").exists())
                .andExpect(jsonPath("$.data.image").exists());
    }

    // ── 시큐어코딩 5(관리자 인가) + 관리자 콘솔 원문 조회 ────────────

    @Test
    void nonAdminCannotApproveAndAdminSeesRawList() throws Exception {
        // 승인 대기 계정 1건 생성(프론트 가입).
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frontendSignup("홍길동", "pendingapprove@company.com", "Abcd1234!", true, false)))
                .andExpect(status().isCreated());
        Long pendingId = userRepository.findByEmail("pendingapprove@company.com").orElseThrow().getId();

        saveApprovedUser("buyer", "buyer@company.com", "Abcd1234!", Role.PURCHASING);
        String buyerToken = loginToken("buyer@company.com", "Abcd1234!");

        // 비관리자(구매팀) 토큰으로 승인 시도 → 403.
        mockMvc.perform(post("/api/v1/auth/users/" + pendingId + "/approve")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        saveApprovedUser("root", "root@company.com", "Abcd1234!", Role.ADMIN);
        String adminToken = loginToken("root@company.com", "Abcd1234!");

        // 관리자 콘솔 목록 조회 → 이름·이메일 원문(관리자는 인가된 처리자).
        mockMvc.perform(get("/api/v1/auth/users?status=PENDING")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.user_id == " + pendingId + ")].name").value("홍길동"))
                .andExpect(jsonPath("$.data[?(@.user_id == " + pendingId + ")].email")
                        .value("pendingapprove@company.com"));

        // 관리자 승인 → APPROVED.
        mockMvc.perform(post("/api/v1/auth/users/" + pendingId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertTrue(userRepository.findById(pendingId).orElseThrow().isApproved());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private User saveApprovedUser(String username, String email, String rawPassword, Role role) {
        return userRepository.save(new User(
                username, passwordEncoder.encode(rawPassword), username + "-이름", role,
                email, "OO배터리", ApprovalStatus.APPROVED));
    }

    private String loginToken(String email, String password) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/access_token").asText();
    }
}
