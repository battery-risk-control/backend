package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    // 관리자 승인 목록 조회 — 최근 신청이 위로 오도록 생성 역순 정렬한다.
    // 관리자(ADMIN) 계정은 목록에 노출하지 않는다(표적화 방지) — role != ADMIN만 반환한다.
    List<User> findByApprovalStatusAndRoleNotOrderByCreatedAtDesc(ApprovalStatus approvalStatus, Role role);

    // [surin F10] NotificationService가 CRITICAL/DIGEST 알림 수신자를 조회한다: 지정 역할 + 활성 + 이메일 보유.
    // 승인(APPROVED) 계정만 수신 대상이다 — 승인 대기/거부 계정에 리스크 브리핑이 나가면
    // 정보 유출이고, 그런 계정은 대개 미검증(가짜) 주소라 발송이 바운스된다.
    List<User> findByRoleInAndEnabledTrueAndApprovalStatusAndEmailIsNotNull(
            List<Role> roles, ApprovalStatus approvalStatus);
}
