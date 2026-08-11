package com.example.batteryrisk.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자동입력 방지(캡챠) 발급 응답. 규제 가이드 제4조 접근통제(캡챠) 대응.
 *
 * <p>{@code image}는 {@code data:image/png;base64,...} 형태의 Data URL이라 프론트에서
 * {@code <img src>}로 바로 렌더링한다. 로그인 요청 시 {@code captchaId}와 사용자가 입력한 값을 함께 보낸다.
 */
public record CaptchaResponse(
        @Schema(description = "캡챠 발급 ID. 로그인 요청의 captcha_id로 그대로 보낸다.")
        String captchaId,

        @Schema(description = "캡챠 이미지(data:image/png;base64,...)")
        String image,

        @Schema(description = "만료까지 남은 초")
        long expiresIn
) {
}
