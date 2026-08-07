package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 로그인 응답.
 *
 * <p>기존 토큰 필드에 더해 프론트엔드가 최상위에서 바로 읽는
 * {@code org_tier}/{@code status}를 함께 내려준다.
 *
 * <p>refresh 토큰은 HttpOnly 쿠키로만 내려 JS 노출을 막는다({@link #withoutRefreshToken()}).
 * {@code @JsonInclude(NON_NULL)}이라 refreshToken이 null이면 body에서 아예 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    /** refresh 토큰을 body에서 뺀 사본. 컨트롤러가 HttpOnly 쿠키로만 내리므로 응답 body엔 노출하지 않는다. */
    public LoginResponse withoutRefreshToken() {
        return new LoginResponse(
                accessToken, null, tokenType, expiresIn, refreshExpiresIn, orgTier, status, user);
    }
}
