package com.example.batteryrisk.dto.auth;

import com.example.batteryrisk.domain.User;

public record UserSummary(
        Long id,
        String username,
        String name,
        String role
) {
    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getName(), user.getRole().name());
    }
}
