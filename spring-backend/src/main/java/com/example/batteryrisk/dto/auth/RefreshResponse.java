package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
) {
    public static RefreshResponse of(String accessToken, long expiresIn) {
        return new RefreshResponse(accessToken, "Bearer", expiresIn);
    }
}
