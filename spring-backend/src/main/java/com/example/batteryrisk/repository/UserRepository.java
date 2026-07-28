package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    // [surin F10] NotificationService가 CRITICAL/DIGEST 알림 수신자를 조회한다: 지정 역할 + 활성 + 이메일 보유.
    List<User> findByRoleInAndEnabledTrueAndEmailIsNotNull(List<Role> roles);
}
