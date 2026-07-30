package com.example.batteryrisk.domain;

import java.util.Locale;

/**
 * 사용자 계층.
 *
 * <p>DB와 JWT는 이 enum 이름(PURCHASING/STRATEGY/EXECUTIVE)을 그대로 쓰고,
 * 프론트엔드 계약인 {@code org_tier}(purchasing/planning/executive)는 여기서만 변환한다.
 * 특히 2계층은 백엔드 {@code STRATEGY} ↔ 프론트엔드 {@code planning}으로 단어가 다르다.
 */
public enum Role {
    PURCHASING("purchasing"),
    STRATEGY("planning"),
    EXECUTIVE("executive");

    private final String orgTier;

    Role(String orgTier) {
        this.orgTier = orgTier;
    }

    /** 프론트엔드 계약의 org_tier 값을 반환한다. */
    public String getOrgTier() {
        return orgTier;
    }

    /** org_tier(purchasing/planning/executive) 또는 Role 이름을 Role로 해석한다. */
    public static Role fromOrgTier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Role role : values()) {
            if (role.orgTier.equals(normalized) || role.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return role;
            }
        }
        return null;
    }
}
