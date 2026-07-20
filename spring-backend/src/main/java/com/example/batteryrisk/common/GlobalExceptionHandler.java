package com.example.batteryrisk.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                false,
                new ApiErrorResponse.ErrorBody(
                        exception.getCode(),
                        exception.getMessage(),
                        null
                ),
                OffsetDateTime.now()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(
            MethodArgumentTypeMismatchException exception
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                false,
                new ApiErrorResponse.ErrorBody(
                        "INVALID_REQUEST",
                        "요청 파라미터가 올바르지 않습니다: " + exception.getName(),
                        null
                ),
                OffsetDateTime.now()
        );
        return ResponseEntity.badRequest().body(response);
    }
}
