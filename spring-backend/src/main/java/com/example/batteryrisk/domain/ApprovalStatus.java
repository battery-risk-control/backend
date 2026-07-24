package com.example.batteryrisk.domain;

/** 관리자 승인 상태. 프론트엔드 승인 대기 화면(PENDING_APPROVAL)과 대응한다. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
