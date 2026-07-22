package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.RevokedTokenSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RevokedTokenSessionRepository extends JpaRepository<RevokedTokenSession, String> {
    boolean existsBySessionIdAndExpiresAtAfter(String sessionId, Instant now);

    long deleteByExpiresAtBefore(Instant now);
}
