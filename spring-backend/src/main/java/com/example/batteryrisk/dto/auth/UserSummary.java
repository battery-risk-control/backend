package com.example.batteryrisk.dto.auth;

import com.example.batteryrisk.domain.User;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 사용자 요약.
 *
 * <p>기존 필드(id/username/name/role)는 그대로 두고, 프론트엔드 계약에 필요한
 * {@code user_id}/{@code email}/{@code org_tier}/{@code status}를 추가로 내려준다.
 */
public record UserSummary(
        Long id,
        String username,
        String name,
        String role,
        @JsonProperty("user_id") String userId,
        String email,
        @JsonProperty("org_tier") String orgTier,
        String status
) {
    public static UserSummary from(User user) {
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getRole().name(),
                String.valueOf(user.getId()),
                user.getEmail(),
                user.getRole().getOrgTier(),
                user.getApprovalStatus().name());
    }
}
