package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        // 프론트 부트스트랩(F5 세션 복원)이 refresh 한 번으로 끝나도록 계정 표시 정보를 함께 내린다.
        // refresh()가 이미 사용자를 로드하므로 추가 조회 비용은 없다. 이 두 필드가 없으면 프론트는
        // 기존처럼 /auth/me를 한 번 더 불러야 해서 새로고침마다 왕복이 하나 더 생긴다.
        @JsonProperty("org_tier") String orgTier,
        @JsonProperty("username") String username
) {
    public static RefreshResponse of(String accessToken, long expiresIn, String orgTier, String username) {
        return new RefreshResponse(accessToken, "Bearer", expiresIn, orgTier, username);
    }
}
