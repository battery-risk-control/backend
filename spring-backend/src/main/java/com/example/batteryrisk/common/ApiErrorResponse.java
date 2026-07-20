package com.example.batteryrisk.common;

import java.time.OffsetDateTime;

public record ApiErrorResponse(
        boolean success,
        ErrorBody error,
        OffsetDateTime timestamp
) {
    public record ErrorBody(
            String code,
            String message,
            Object details
    ) {
    }
}