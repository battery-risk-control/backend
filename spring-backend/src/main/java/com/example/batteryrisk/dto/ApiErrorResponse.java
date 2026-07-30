package com.example.batteryrisk.dto;

import java.time.OffsetDateTime;

/** Security Filter와 Controller 예외가 공유하는 실패 응답 Envelope입니다. */
public record ApiErrorResponse(
        boolean success,
        ErrorBody error,
        OffsetDateTime timestamp
) {
    public record ErrorBody(String code, String message, Object details) {}

    public static ApiErrorResponse of(String code, String message) {
        return of(code, message, null);
    }

    public static ApiErrorResponse of(String code, String message, Object details) {
        return new ApiErrorResponse(
                false, new ErrorBody(code, message, details), OffsetDateTime.now());
    }
}
