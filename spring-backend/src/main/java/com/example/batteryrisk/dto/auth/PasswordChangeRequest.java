package com.example.batteryrisk.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 변경 요청. 두 경로가 공유한다.
 * <ul>
 *   <li>만료 재설정(POST /auth/password/reset-expired): 로그인 불가 상태라 {@code email}로 대상을 지정한다.</li>
 *   <li>로그인 상태 변경(PUT /auth/password): 인증 주체가 대상이므로 {@code email}은 무시된다.</li>
 * </ul>
 * 새 비밀번호는 회원가입과 동일한 복잡도 규칙({@link ValidPassword})을 적용한다.
 */
public record PasswordChangeRequest(
        @Schema(example = "user@company.com", description = "만료 재설정 대상 이메일(로그인 상태 변경 시 무시)")
        String email,

        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        @ValidPassword
        String newPassword
) {
    public String normalizedEmail() {
        return email == null ? null : email.trim();
    }
}
