package com.example.batteryrisk.dto.auth;

import com.example.batteryrisk.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record SignupRequest(
        @NotBlank(message = "아이디를 입력해 주세요.")
        @Size(min = 4, max = 50, message = "아이디는 4자 이상 50자 이하로 입력해 주세요.")
        String username,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상으로 입력해 주세요.")
        String password,

        @NotBlank(message = "이름을 입력해 주세요.")
        String name,

        @Schema(description = "소속 역할: 구매팀, 경영기획팀, 경영진")
        @NotNull(message = "소속 계층(역할)을 선택해 주세요.")
        Role role
) {
}
