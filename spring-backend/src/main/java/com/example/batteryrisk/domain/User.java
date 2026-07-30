package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void disable() {
        this.enabled = false;
    }
}
