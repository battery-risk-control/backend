package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.auth.SignupRequest;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User signUp(SignupRequest request) {
        String username = request.resolvedUsername();
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "아이디 또는 이메일을 입력해 주세요.");
        }
        Role role = request.resolvedRole();
        if (role == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "소속 계층(org_tier 또는 role)을 선택해 주세요.");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }

        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                request.name(),
                role,
                blankToNull(request.email()),
                blankToNull(request.orgName()),
                request.requiresApproval() ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED);

        return userRepository.save(user);
    }

    /** 관리자 승인 — 승인 대기 계정을 로그인 가능 상태로 바꾼다. */
    @Transactional
    public User approve(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.approve();
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
