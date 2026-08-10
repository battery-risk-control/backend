package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 100)
    private String name;

    /** 프론트엔드 로그인 식별자. 기존 username 기반 계정은 비어 있을 수 있다. */
    @Column(unique = true, length = 255)
    private String email;

    @Column(name = "org_name", length = 150)
    private String orgName;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 연속 로그인 실패 횟수. 성공 또는 비밀번호 재설정 시 0으로 리셋한다. */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    /** 이 시각까지 로그인을 차단한다. NULL이거나 과거면 잠금 아님. */
    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    /** 비밀번호 최종 변경 시각. +90일 경과 시 재설정을 요구한다. */
    @Column(name = "password_changed_at", nullable = false)
    private OffsetDateTime passwordChangedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected User() {
    }

    public User(String username, String password, String name, Role role) {
        this(username, password, name, role, null, null, ApprovalStatus.APPROVED);
    }

    public User(
            String username, String password, String name, Role role,
            String email, String orgName, ApprovalStatus approvalStatus) {
        this.username = username;
        this.password = password;
        this.name = name;
        this.role = role;
        this.email = email;
        this.orgName = orgName;
        this.approvalStatus = approvalStatus == null ? ApprovalStatus.APPROVED : approvalStatus;
        this.enabled = true;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.passwordChangedAt == null) {
            this.passwordChangedAt = now;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getEmail() {
        return email;
    }

    public String getOrgName() {
        return orgName;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public boolean isApproved() {
        return approvalStatus == ApprovalStatus.APPROVED;
    }

    public void approve() {
        this.approvalStatus = ApprovalStatus.APPROVED;
    }

    /** 관리자 거부 — 로그인 불가 상태로 표시한다. */
    public void reject() {
        this.approvalStatus = ApprovalStatus.REJECTED;
    }

    /** 거부 해제 — 다시 승인 대기 상태로 되돌린다. */
    public void markPending() {
        this.approvalStatus = ApprovalStatus.PENDING;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.passwordChangedAt = OffsetDateTime.now();
        resetLoginFailures();
    }

    public void disable() {
        this.enabled = false;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public OffsetDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    /** 로그인 실패를 1회 기록하고, 상한(maxFailures)에 도달하면 lockDuration만큼 잠근다. */
    public void registerLoginFailure(int maxFailures, Duration lockDuration) {
        this.failedLoginCount += 1;
        if (this.failedLoginCount >= maxFailures) {
            this.lockedUntil = OffsetDateTime.now().plus(lockDuration);
        }
    }

    /** 로그인 성공/재설정 시 실패 카운트와 잠금을 모두 해제한다. */
    public void resetLoginFailures() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isPasswordExpired(OffsetDateTime now, long maxAgeDays) {
        return passwordChangedAt != null && passwordChangedAt.plusDays(maxAgeDays).isBefore(now);
    }
}
