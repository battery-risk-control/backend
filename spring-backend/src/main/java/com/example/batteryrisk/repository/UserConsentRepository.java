package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
    List<UserConsent> findByUserId(Long userId);
}
