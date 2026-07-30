package com.example.batteryrisk.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @JsonProperty("refresh_token")
        @NotBlank(message = "refresh_token을 입력해 주세요.") String refreshToken
) {
}
