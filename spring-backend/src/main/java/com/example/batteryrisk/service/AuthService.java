package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.auth.LoginRequest;
import com.example.batteryrisk.dto.auth.LoginResponse;
import com.example.batteryrisk.dto.auth.PasswordChangeRequest;
import com.example.batteryrisk.dto.auth.RefreshResponse;
import com.example.batteryrisk.dto.auth.UserSummary;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.UserRepository;
import com.example.batteryrisk.security.CustomUserDetails;
import com.example.batteryrisk.security.CustomUserDetailsService;
import com.example.batteryrisk.security.JwtTokenProvider;
import com.example.batteryrisk.security.TokenBlacklistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final CaptchaService captchaService;
    private final PasswordEncoder passwordEncoder;

    /** 로그인 실패가 이 횟수 이상 누적되면 캡챠를 요구한다. */
    @Value("${app.auth.captcha-threshold:3}")
    private int captchaThreshold;

    /** 비밀번호 유효기간(일). 초과 시 로그인에서 재설정을 요구한다. */
    @Value("${app.auth.password.max-age-days:90}")
    private long passwordMaxAgeDays;

    public AuthService(
            AuthenticationManager authenticationManager,
            CustomUserDetailsService userDetailsService,
            JwtTokenProvider jwtTokenProvider,
            TokenBlacklistService tokenBlacklistService,
            UserRepository userRepository,
            UserService userService,
            CaptchaService captchaService,
            PasswordEncoder passwordEncoder
    ) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.captchaService = captchaService;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(LoginRequest request) {
        String loginId = request.loginId();
        if (loginId == null || loginId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이메일 또는 아이디를 입력해 주세요.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Optional<User> existing = userRepository.findByUsername(loginId)
                .or(() -> userRepository.findByEmail(loginId));

        // 1) 잠금 계정은 비밀번호 검증 전에 차단한다(실패 카운트를 더 늘리지 않는다).
        existing.filter(user -> user.isLocked(now)).ifPresent(user -> {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        });

        // 2) 실패가 임계 이상 누적된 계정은 캡챠를 통과해야 한다(비밀번호 검증 전 게이트).
        if (existing.map(user -> user.getFailedLoginCount() >= captchaThreshold).orElse(false)) {
            if (isBlank(request.captchaId()) || isBlank(request.captchaAnswer())) {
                throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED);
            }
            if (!captchaService.validate(request.captchaId(), request.captchaAnswer())) {
                throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
            }
        }

        // 3) 비밀번호 검증.
        CustomUserDetails userDetails;
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginId, request.password()));
            userDetails = (CustomUserDetails) authentication.getPrincipal();
        } catch (DisabledException exception) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        } catch (LockedException exception) {
            // isAccountNonLocked() 심층 방어에서 올라온 경우.
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        } catch (AuthenticationException exception) {
            // 비밀번호 불일치 — 실존 계정이면 실패를 적립하고, 이번 실패로 잠기면 즉시 알린다.
            if (existing.isPresent() && userService.registerLoginFailure(existing.get().getId())) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = userDetails.getUser();

        // 4) 관리자 승인 전 계정은 토큰을 발급하지 않는다(프론트엔드 승인 대기 화면 대응).
        if (!user.isApproved()) {
            throw new BusinessException(ErrorCode.PENDING_APPROVAL);
        }

        // 5) 비밀번호 유효기간 만료 — 토큰을 주지 않고 재설정으로 유도한다.
        if (user.isPasswordExpired(now, passwordMaxAgeDays)) {
            throw new BusinessException(ErrorCode.PASSWORD_EXPIRED);
        }

        // 6) 성공 — 누적 실패·잠금을 리셋한다.
        userService.resetLoginFailures(user.getId());

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(
                userDetails.getId(), userDetails.getUsername(), userDetails.getRole(), sessionId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                userDetails.getId(), userDetails.getUsername(), sessionId);

        return LoginResponse.of(
                accessToken,
                jwtTokenProvider.getAccessTokenValiditySeconds(),
                refreshToken,
                jwtTokenProvider.getRefreshTokenValiditySeconds(),
                UserSummary.from(user));
    }

    /**
     * 만료된 비밀번호 재설정(로그인 불가 상태). email/username으로 대상을 찾아 현재 비밀번호를 검증한다.
     * 잠긴 계정은 막고, 현재 비밀번호가 틀리면 로그인과 동일하게 실패를 적립한다(이 경로의 브루트포스 차단).
     * 실패 적립·비밀번호 변경은 userService(프록시)를 통해 각각 독립 트랜잭션으로 커밋된다.
     */
    public UserSummary resetExpiredPassword(PasswordChangeRequest request) {
        String loginId = request.normalizedEmail();
        if (loginId == null || loginId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이메일을 입력해 주세요.");
        }
        User user = userRepository.findByEmail(loginId)
                .or(() -> userRepository.findByUsername(loginId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (user.isLocked(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            if (userService.registerLoginFailure(user.getId())) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return UserSummary.from(userService.applyNewPassword(user.getId(), request.newPassword()));
    }

    /** 로그인 상태에서 본인 비밀번호 변경. 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾼다. */
    public UserSummary changeMyPassword(Long userId, PasswordChangeRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return UserSummary.from(userService.applyNewPassword(userId, request.newPassword()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public RefreshResponse refresh(String refreshToken) {
        if (jwtTokenProvider.isExpired(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
        if (!jwtTokenProvider.isValid(refreshToken)
                || !JwtTokenProvider.TOKEN_TYPE_REFRESH.equals(jwtTokenProvider.getType(refreshToken))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        String sessionId = jwtTokenProvider.getJti(refreshToken);
        if (tokenBlacklistService.isBlacklisted(sessionId)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        CustomUserDetails userDetails = (CustomUserDetails)
                userDetailsService.loadUserByUsername(jwtTokenProvider.getUsername(refreshToken));
        if (!userDetails.isEnabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                userDetails.getId(), userDetails.getUsername(), userDetails.getRole(), sessionId);

        return RefreshResponse.of(newAccessToken, jwtTokenProvider.getAccessTokenValiditySeconds());
    }

    public void logout(String token) {
        String sessionId = jwtTokenProvider.getJti(token);
        Instant expiresAt = Instant.now().plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds());
        tokenBlacklistService.blacklist(sessionId, expiresAt);
    }

    public UserSummary me(CustomUserDetails userDetails) {
        return UserSummary.from(userDetails.getUser());
    }
}
