package com.example.batteryrisk.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 *
 * <p>프론트엔드는 {@code email}로, 기존 Swagger·테스트는 {@code username}으로 로그인한다.
 * 둘 중 하나만 있으면 되며 {@link #loginId()}가 실제 인증에 쓸 값을 고른다.
 */
public record LoginRequest(
        @Schema(example = "user@company.com", description = "프론트엔드 로그인 식별자")
        String email,

        @Schema(example = "test", description = "기존 방식 로그인 식별자. email이 없을 때 사용")
        String username,

        @NotBlank(message = "비밀번호를 입력해 주세요.") String password
) {
    /** email을 우선 사용하고, 없으면 username을 쓴다. */
    public String loginId() {
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        return username == null ? null : username.trim();
    }
}
