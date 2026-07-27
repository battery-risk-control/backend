package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 로그인 응답.
 *
 * <p>기존 토큰 필드에 더해 프론트엔드가 최상위에서 바로 읽는
 * {@code org_tier}/{@code status}를 함께 내려준다.
 */
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn,
        @JsonProperty("org_tier") String orgTier,
        String status,
        UserSummary user
) {
    public static LoginResponse of(
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            UserSummary user
    ) {
        return new LoginResponse(
                accessToken, refreshToken, "Bearer", expiresIn, refreshExpiresIn,
                user.orgTier(), user.status(), user);
    }
}
