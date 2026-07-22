package com.example.batteryrisk.exception;

import com.example.batteryrisk.dto.DocumentDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public static class DocumentUploadException extends RuntimeException {
        private final String code;

        public DocumentUploadException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String documentId) {
            super("문서를 찾을 수 없습니다: " + documentId);
        }
    }

    @ExceptionHandler(DocumentUploadException.class)
    ResponseEntity<DocumentDto.ErrorResponse> handleDocumentUpload(DocumentUploadException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(DocumentDto.ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<DocumentDto.ErrorResponse> handleDocumentNotFound(DocumentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(DocumentDto.ErrorResponse.of("DOCUMENT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class})
    ResponseEntity<DocumentDto.ErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(DocumentDto.ErrorResponse.of("INVALID_REQUEST", "요청 값을 확인해 주세요."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<DocumentDto.ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unexpected server error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(DocumentDto.ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
