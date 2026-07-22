package com.example.batteryrisk.dto;

import java.time.OffsetDateTime;

/** Spring 공개 API에서 공통으로 사용하는 성공 응답 Envelope입니다. */
public record ApiResponse<T>(
        boolean success,
        T data,
        OffsetDateTime timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, OffsetDateTime.now());
    }
}
