package com.example.batteryrisk.exception;

import com.example.batteryrisk.dto.ApiErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public static class DocumentUploadException extends RuntimeException {
        private final String code;
        private final HttpStatusCode status;

        public DocumentUploadException(String code, String message) {
            this(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        public DocumentUploadException(String code, String message, HttpStatusCode status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String getCode() {
            return code;
        }

        public HttpStatusCode getStatus() {
            return status;
        }
    }

    public static class RagSearchException extends RuntimeException {
        private final String code;
        private final HttpStatusCode status;

        public RagSearchException(String code, String message, HttpStatusCode status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String getCode() {
            return code;
        }

        public HttpStatusCode getStatus() {
            return status;
        }
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String documentId) {
            super("문서를 찾을 수 없습니다: " + documentId);
        }
    }

    // [surin F3] AnalysisService가 존재하지 않는 분석 조회 시 던진다.
    public static class AnalysisNotFoundException extends RuntimeException {
        public AnalysisNotFoundException(String analysisId) {
            super("분석을 찾을 수 없습니다: " + analysisId);
        }
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiErrorResponse.of(errorCode.name(), exception.getMessage()));
    }

    @ExceptionHandler(DocumentUploadException.class)
    ResponseEntity<ApiErrorResponse> handleDocumentUpload(DocumentUploadException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiErrorResponse.of(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(RagSearchException.class)
    ResponseEntity<ApiErrorResponse> handleRagSearch(RagSearchException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiErrorResponse.of(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleDocumentNotFound(DocumentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("DOCUMENT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(AnalysisNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("ANALYSIS_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .orElse("요청 값을 확인해 주세요.")
                : "요청 값을 확인해 주세요.";
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("INVALID_REQUEST", message));
    }

    /**
     * 쿼리 파라미터 제약 위반(@Validated 컨트롤러의 @Min/@Max 등)도 클라이언트 오류(400)로 처리한다.
     *
     * <p>이 핸들러가 없으면 {@code ?days=365} 같은 범위 초과가 제네릭 Exception 핸들러로 떨어져
     * 500이 나간다 — 호출자는 자기 요청이 잘못됐다는 걸 알 방법이 없고 서버 장애로 오인한다.
     * {@code @Validated}를 쓰는 컨트롤러(RiskEventController·RiskMonitoringController)가 대상이다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    // "events.days"처럼 메서드명이 앞에 붙어 오므로 파라미터 이름만 남긴다.
                    String parameter = path.substring(path.lastIndexOf('.') + 1);
                    return parameter + ": " + violation.getMessage();
                })
                .orElse("요청 값을 확인해 주세요.");
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("INVALID_REQUEST", message));
    }

    // 요청 본문을 읽지 못하는 경우(깨진 JSON, UTF-8이 아닌 인코딩 등)는 클라이언트 오류(400)로 처리한다.
    // 이 핸들러가 없으면 제네릭 Exception 핸들러로 떨어져 500이 나가, 인코딩 실수를 서버 오류로 오인하게 된다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                "MALFORMED_REQUEST_BODY",
                "요청 본문을 읽을 수 없습니다. JSON 형식과 UTF-8 인코딩을 확인해 주세요."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.of("FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        String message = duplicateMessageFor(detail);
        if (message == null) {
            log.warn("Unmapped data integrity violation", exception);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorResponse.of("DATA_INTEGRITY_VIOLATION", "데이터 제약 조건을 위반했습니다."));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("DUPLICATE_KEY", message));
    }

    private static String duplicateMessageFor(String detail) {
        if (detail == null) {
            return null;
        }
        if (detail.contains("uq_material_code")) return "이미 사용 중인 자재 코드입니다.";
        if (detail.contains("uq_supplier_code")) return "이미 사용 중인 공급사 코드입니다.";
        if (detail.contains("uq_supplier_registration")) return "이미 등록된 사업자등록번호입니다.";
        if (detail.contains("uq_warehouse_code")) return "이미 사용 중인 창고 코드입니다.";
        if (detail.contains("uq_contract_number")) return "이미 사용 중인 계약 번호입니다.";
        if (detail.contains("uq_purchase_order_number")) return "이미 사용 중인 발주 번호입니다.";
        if (detail.contains("uq_supplier_material_contract")) return "이미 등록된 공급사·자재·계약 조합입니다.";
        return null;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                "INVALID_REQUEST", "요청 파라미터가 올바르지 않습니다: " + exception.getName()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of(
                ErrorCode.AUTHENTICATION_REQUIRED.name(),
                ErrorCode.AUTHENTICATION_REQUIRED.getDefaultMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of(
                ErrorCode.ACCESS_DENIED.name(), ErrorCode.ACCESS_DENIED.getDefaultMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("RESOURCE_NOT_FOUND", "요청한 API를 찾을 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unexpected server error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
