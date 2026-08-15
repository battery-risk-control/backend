package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.ApprovalStatus;
import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.domain.UserConsent;
import com.example.batteryrisk.dto.auth.SignupRequest;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.UserConsentRepository;
import com.example.batteryrisk.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final PasswordEncoder passwordEncoder;

    /** 계정 잠금 정책. 연속 실패 max-failures회에 도달하면 lock-minutes분 잠근다. */
    @Value("${app.auth.lockout.max-failures:5}")
    private int lockoutMaxFailures;

    @Value("${app.auth.lockout.lock-minutes:5}")
    private long lockoutMinutes;

    public UserService(
            UserRepository userRepository,
            UserConsentRepository userConsentRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userConsentRepository = userConsentRepository;
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
        // 관리자(ADMIN)는 가입으로 획득할 수 없다. 시드로만 만든다.
        if (role == Role.ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "선택할 수 없는 소속 계층입니다.");
        }

        boolean frontendSignup = request.requiresApproval();
        String email = blankToNull(request.email());

        // 프론트엔드 가입 경로에서만 이메일 필수·중복·필수 동의를 검사한다.
        // (기존 username/role 가입 경로는 email이 없고 동의 개념도 없으므로 그대로 둔다.)
        if (frontendSignup) {
            if (email == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "회사 이메일을 입력해 주세요.");
            }
            if (!request.privacyRequiredAgreed()) {
                throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
            }
            if (userRepository.existsByEmail(email)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }

        User user = userRepository.save(new User(
                username,
                passwordEncoder.encode(request.password()),
                request.name(),
                role,
                email,
                blankToNull(request.orgName()),
                frontendSignup ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED));

        // 동의 이력은 프론트엔드 가입에서만 남긴다(감사 추적). 필수는 위에서 true를 보장했다.
        if (frontendSignup) {
            userConsentRepository.saveAll(List.of(
                    new UserConsent(user.getId(), UserConsent.ConsentType.PRIVACY_REQUIRED, true),
                    new UserConsent(user.getId(), UserConsent.ConsentType.MARKETING_OPTIONAL,
                            request.marketingOptionalAgreed())));
        }

        return user;
    }

    /** 관리자 승인 — 승인 대기 계정을 로그인 가능 상태로 바꾼다. */
    @Transactional
    public User approve(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.approve();
        return user;
    }

    /** 관리자 거부 — 계정을 거부(REJECTED) 상태로 바꾼다(승인 대기·승인 완료 모두에서 가능). */
    @Transactional
    public User reject(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.reject();
        return user;
    }

    /** 거부 해제 — 거부된 계정을 다시 승인 대기(PENDING)로 되돌린다. */
    @Transactional
    public User reopen(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.markPending();
        return user;
    }

    /** 관리자 승인 목록 조회 — 지정 상태의 계정을 최근 신청 순으로 반환한다. 권한 계정(ADMIN·MASTER)은 제외한다. */
    public List<User> findByApprovalStatus(ApprovalStatus status) {
        return userRepository.findByApprovalStatusAndRoleNotInOrderByCreatedAtDesc(
                status, List.of(Role.ADMIN, Role.MASTER));
    }

    /**
     * 로그인 실패를 1회 기록한다. 상한에 도달하면 잠근다. 잠금 여부를 반환한다.
     *
     * <p>호출자(AuthService.login)는 비트랜잭션이므로 이 메서드는 자신의 트랜잭션에서
     * 커밋된다 — 로그인이 최종 실패로 던져져도 실패 카운트는 남는다(브루트포스 방지의 핵심).
     */
    @Transactional
    public boolean registerLoginFailure(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        user.registerLoginFailure(lockoutMaxFailures, Duration.ofMinutes(lockoutMinutes));
        return user.isLocked(OffsetDateTime.now());
    }

    /** 로그인 성공 시 실패 카운트·잠금을 리셋한다(변화가 있을 때만 기록). */
    @Transactional
    public void resetLoginFailures(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && (user.getFailedLoginCount() > 0 || user.getLockedUntil() != null)) {
            user.resetLoginFailures();
        }
    }

    /**
     * 새 비밀번호를 적용한다(직전 비밀번호 재사용 금지). passwordChangedAt 갱신과 실패 카운트/잠금
     * 리셋은 {@link User#changePassword}가 함께 처리한다. 현재 비밀번호 검증·실패 적립은 호출자
     * (AuthService)가 프록시를 통해 registerLoginFailure를 별도 트랜잭션으로 커밋시킨 뒤 부른다.
     */
    @Transactional
    public User applyNewPassword(Long userId, String newRawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (passwordEncoder.matches(newRawPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED);
        }
        user.changePassword(passwordEncoder.encode(newRawPassword));
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
