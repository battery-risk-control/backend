package com.example.batteryrisk.dto.auth;

import com.example.batteryrisk.domain.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청.
 *
 * <p>두 가지 형태를 모두 받는다.
 * <ul>
 *   <li>프론트엔드: {@code {name, email, password, org_tier, org_name, privacy_required_consent, ...}}
 *       → 승인 대기(PENDING)로 생성. 필수 동의·이메일 형식·중복은 {@code UserService}에서 검사한다.</li>
 *   <li>기존 방식: {@code {username, password, name, role}} → 즉시 승인(APPROVED)으로 생성</li>
 * </ul>
 */
public record SignupRequest(
        @Size(min = 4, max = 50, message = "아이디는 4자 이상 50자 이하로 입력해 주세요.")
        @Schema(example = "test", description = "기존 방식 아이디. 없으면 email을 아이디로 사용")
        String username,

        // @NotBlank는 붙이지 않는다 — 기존 username 기반 가입 경로에는 email이 없기 때문이다.
        // 프론트엔드 가입 경로의 email 필수 여부는 UserService.signUp에서 검사한다.
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Schema(example = "user@company.com", description = "프론트엔드 로그인 식별자")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @ValidPassword
        String password,

        @NotBlank(message = "이름을 입력해 주세요.")
        String name,

        @Schema(description = "기존 방식 역할: PURCHASING/STRATEGY/EXECUTIVE")
        Role role,

        @JsonProperty("org_tier")
        @Schema(example = "planning", description = "프론트엔드 계층: purchasing/planning/executive")
        String orgTier,

        @JsonProperty("org_name")
        @Schema(example = "OO배터리", description = "소속 회사명")
        String orgName,

        @JsonProperty("privacy_required_consent")
        @Schema(example = "true", description = "[필수] 개인정보 수집·이용 동의. 프론트엔드 가입 시 true여야 한다.")
        Boolean privacyRequiredConsent,

        @JsonProperty("marketing_optional_consent")
        @Schema(example = "false", description = "[선택] 이벤트·뉴스 수신 동의")
        Boolean marketingOptionalConsent
) {
    /** username이 없으면 email을 아이디로 사용한다. */
    public String resolvedUsername() {
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return email == null ? null : email.trim();
    }

    /** role이 없으면 org_tier로 해석한다. */
    public Role resolvedRole() {
        return role != null ? role : Role.fromOrgTier(orgTier);
    }

    /** org_tier로 들어온 프론트엔드 가입만 관리자 승인 대기로 시작한다. */
    public boolean requiresApproval() {
        return role == null && orgTier != null && !orgTier.isBlank();
    }

    /** 필수 개인정보 수집·이용에 동의했는가. */
    public boolean privacyRequiredAgreed() {
        return Boolean.TRUE.equals(privacyRequiredConsent);
    }

    /** 선택 이벤트·뉴스 수신에 동의했는가(미지정은 미동의로 본다). */
    public boolean marketingOptionalAgreed() {
        return Boolean.TRUE.equals(marketingOptionalConsent);
    }
}
