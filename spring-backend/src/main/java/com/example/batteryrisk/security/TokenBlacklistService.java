package com.example.batteryrisk.security;

import com.example.batteryrisk.domain.RevokedTokenSession;
import com.example.batteryrisk.repository.RevokedTokenSessionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TokenBlacklistService {
    private final RevokedTokenSessionRepository repository;

    public TokenBlacklistService(RevokedTokenSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void blacklist(String jti, Instant expiresAt) {
        repository.saveAndFlush(new RevokedTokenSession(jti, expiresAt));
    }

    @Transactional(readOnly = true)
    public boolean isBlacklisted(String jti) {
        return repository.existsBySessionIdAndExpiresAtAfter(jti, Instant.now());
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional
    void evictExpiredEntries() {
        repository.deleteByExpiresAtBefore(Instant.now());
    }
}
