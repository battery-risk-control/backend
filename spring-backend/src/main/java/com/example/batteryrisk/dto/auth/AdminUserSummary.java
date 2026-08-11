package com.example.batteryrisk.dto.auth;

import com.example.batteryrisk.domain.User;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 관리자 콘솔용 사용자 요약.
 *
 * <p>이 응답은 ADMIN 전용 엔드포인트(가입 관리)에서만 쓰이고, 관리자는 인가된 개인정보 처리자이므로
 * 이름·아이디·이메일을 <b>원문 그대로</b> 내려준다. 규제 ③(개인정보 표시 제한)의 마스킹은 일반 표시
 * 맥락(헤더 등 모든 사용자 자기 계정 표시)에서 유지된다 — 접근통제된 관리자 콘솔은 그 대상이 아니다.
 *
 * <p>{@code username}은 로그인 식별자다(헤더 표시·로그인과 동일). {@code email}은 알림 수신용 컬럼으로,
 * 실제 프론트 가입 계정은 username==email이지만 테스트 시드는 email을 실제 받은편지함 주소로 덮으므로
 * 둘이 다를 수 있다 — 관리자 화면은 계정 식별에 username을 쓴다.
 */
public record AdminUserSummary(
        @JsonProperty("user_id") Long userId,
        String name,
        String username,
        String email,
        @JsonProperty("org_tier") String orgTier,
        @JsonProperty("org_name") String orgName,
        String status,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
    public static AdminUserSummary from(User user) {
        return new AdminUserSummary(
                user.getId(),
                user.getName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().getOrgTier(),
                user.getOrgName(),
                user.getApprovalStatus().name(),
                user.getCreatedAt());
    }
}
