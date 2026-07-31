package com.example.batteryrisk.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 토큰이 유효하지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 API에 접근할 권한이 없습니다."),
    DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    PENDING_APPROVAL(HttpStatus.FORBIDDEN, "관리자 승인 대기 중입니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
    ERP_MATERIAL_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 자재를 찾을 수 없습니다."),
    ERP_SUPPLIER_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 공급사를 찾을 수 없습니다."),
    ERP_WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 창고를 찾을 수 없습니다."),
    ERP_CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 계약을 찾을 수 없습니다."),
    ERP_PURCHASE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 발주를 찾을 수 없습니다."),
    ERP_PURCHASE_ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 발주 품목을 찾을 수 없습니다."),
    ERP_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 완제품을 찾을 수 없습니다."),
    ERP_CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "ERP 고객사를 찾을 수 없습니다."),
    ERP_CONTEXT_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "ERP 분석 Context를 구성할 수 없습니다."),
    FASTAPI_SEVERITY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "FastAPI Severity 분석을 사용할 수 없습니다."),
    INVALID_SEVERITY_RESPONSE(HttpStatus.BAD_GATEWAY, "FastAPI Severity 응답이 올바르지 않습니다."),
    SEVERITY_ASSESSMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Severity 분석 결과를 찾을 수 없습니다."),
    FASTAPI_BRIEFING_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "FastAPI 브리핑 생성을 사용할 수 없습니다."),
    INVALID_BRIEFING_RESPONSE(HttpStatus.BAD_GATEWAY, "FastAPI 브리핑 응답이 올바르지 않습니다."),
    BRIEFING_NOT_FOUND(HttpStatus.NOT_FOUND, "브리핑을 찾을 수 없습니다."),
    ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."),
    ANALYSIS_NOT_SCORED(HttpStatus.UNPROCESSABLE_ENTITY, "외부신호 점수가 산출되지 않은 분석입니다."),
    ANALYSIS_NOT_SUPPLY_CHAIN_RELEVANT(
            HttpStatus.UNPROCESSABLE_ENTITY, "공급망과 무관한 뉴스로 판정된 분석입니다."),
    EXTERNAL_SIGNAL_REQUIRED(
            HttpStatus.BAD_REQUEST, "analysis_id 또는 external_signal_score·external_signal_level이 필요합니다."),
    PROCUREMENT_RISK_ASSESSMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND, "구매 리스크 점수를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
