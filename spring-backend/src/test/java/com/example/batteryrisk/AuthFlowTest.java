package com.example.batteryrisk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.batteryrisk.repository.RevokedTokenSessionRepository;
import com.example.batteryrisk.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RevokedTokenSessionRepository revokedTokenSessionRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @Test
    void signupLoginMeLogoutWorkForEachTier() throws Exception {
        String signupBody = objectMapper.writeValueAsString(
                new SignupPayload("purchaser1", "Purchase123!", "구매팀원", "PURCHASING"));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("PURCHASING"));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_USERNAME"));

        String responseJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("purchaser1", "Purchase123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.user.role").value("PURCHASING"))
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(responseJson).at("/data/access_token").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("purchaser1"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertTrue(revokedTokenSessionRepository.existsById(jwtTokenProvider.getJti(accessToken)));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenIssuesNewAccessTokenAndIsInvalidatedTogetherOnLogout() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("strategist1", "Strategy123!", "경영기획팀원", "STRATEGY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("STRATEGY"));

        MockHttpServletResponse loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("strategist1", "Strategy123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                // refresh 토큰은 body가 아니라 HttpOnly 쿠키로만 내려온다.
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                .andReturn().getResponse();

        String accessToken = objectMapper.readTree(loginResponse.getContentAsString())
                .at("/data/access_token").asText();
        Cookie refreshCookie = loginResponse.getCookie("refresh_token");
        assertNotNull(refreshCookie);
        assertTrue(refreshCookie.isHttpOnly());

        // refresh 토큰을 access token 자리(Bearer)에 넣으면 인증에 실패해야 한다.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + refreshCookie.getValue()))
                .andExpect(status().isUnauthorized());

        // refresh는 요청 body가 아니라 쿠키로 한다.
        String refreshResponseJson = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andReturn().getResponse().getContentAsString();

        String newAccessToken = objectMapper.readTree(refreshResponseJson)
                .at("/data/access_token").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("strategist1"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 로그아웃으로 세션이 블랙리스트되면 같은 refresh 쿠키로도 재발급이 막힌다.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void loginWithWrongPasswordReturnsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("exec1", "Executive123!", "경영진 임원", "EXECUTIVE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("EXECUTIVE"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("exec1", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void apiPathsRequireAuthentication() throws Exception {
        // 14단계에서 대시보드 집계가 ERP 내부 값(재고일수·의존도)을 반환하게 되면서 인증 필수로 바뀌었다.
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/risks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    private record SignupPayload(String username, String password, String name, String role) {}
    private record LoginPayload(String username, String password) {}
}
