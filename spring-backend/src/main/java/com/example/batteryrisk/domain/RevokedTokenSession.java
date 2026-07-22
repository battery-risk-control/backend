package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 로그아웃된 JWT 세션을 Spring 재시작 후에도 차단하기 위한 영속 Entity입니다. */
@Entity
@Table(name = "revoked_token_sessions")
public class RevokedTokenSession {

    @Id
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private Instant revokedAt;

    protected RevokedTokenSession() {}

    public RevokedTokenSession(String sessionId, Instant expiresAt) {
        this.sessionId = sessionId;
        this.expiresAt = expiresAt;
        this.revokedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
