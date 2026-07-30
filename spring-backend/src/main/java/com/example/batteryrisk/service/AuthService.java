package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.auth.LoginRequest;
import com.example.batteryrisk.dto.auth.LoginResponse;
import com.example.batteryrisk.dto.auth.RefreshRequest;
import com.example.batteryrisk.dto.auth.RefreshResponse;
import com.example.batteryrisk.dto.auth.UserSummary;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.security.CustomUserDetails;
import com.example.batteryrisk.security.CustomUserDetailsService;
import com.example.batteryrisk.security.JwtTokenProvider;
import com.example.batteryrisk.security.TokenBlacklistService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(
            AuthenticationManager authenticationManager,
            CustomUserDetailsService userDetailsService,
            JwtTokenProvider jwtTokenProvider,
            TokenBlacklistService tokenBlacklistService
    ) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public LoginResponse login(LoginRequest request) {
        String loginId = request.loginId();
        if (loginId == null || loginId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이메일 또는 아이디를 입력해 주세요.");
        }

        CustomUserDetails userDetails;
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginId, request.password()));
            userDetails = (CustomUserDetails) authentication.getPrincipal();
        } catch (DisabledException exception) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        } catch (AuthenticationException exception) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 관리자 승인 전 계정은 토큰을 발급하지 않는다(프론트엔드 승인 대기 화면 대응).
        if (!userDetails.getUser().isApproved()) {
            throw new BusinessException(ErrorCode.PENDING_APPROVAL);
        }

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
                UserSummary.from(userDetails.getUser()));
    }

    public RefreshResponse refresh(RefreshRequest request) {
        String refreshToken = request.refreshToken();

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
