package com.example.batteryrisk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.batteryrisk.repository.RevokedTokenSessionRepository;
import com.example.batteryrisk.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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

        String loginJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("strategist1", "Strategy123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refresh_token").exists())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(loginJson).at("/data/access_token").asText();
        String refreshToken = objectMapper.readTree(loginJson).at("/data/refresh_token").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());

        String refreshResponseJson = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
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

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
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
    void dashboardIsPublicButOtherApiPathsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/risks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    private record SignupPayload(String username, String password, String name, String role) {}
    private record LoginPayload(String username, String password) {}

    private record RefreshPayload(
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken
    ) {}
}
