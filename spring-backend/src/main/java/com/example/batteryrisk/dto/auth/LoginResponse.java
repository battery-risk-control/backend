package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn,
        UserSummary user
) {
    public static LoginResponse of(
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            UserSummary user
    ) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn, refreshExpiresIn, user);
    }
}
