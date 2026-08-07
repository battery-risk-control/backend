package com.example.batteryrisk.controller;

import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.auth.LoginRequest;
import com.example.batteryrisk.dto.auth.LoginResponse;
import com.example.batteryrisk.dto.auth.RefreshResponse;
import com.example.batteryrisk.dto.auth.SignupRequest;
import com.example.batteryrisk.dto.auth.UserSummary;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.security.CustomUserDetails;
import com.example.batteryrisk.service.AuthService;
import com.example.batteryrisk.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    /** refresh 토큰 HttpOnly 쿠키 이름. /auth 하위에서만 전송되도록 Path를 좁힌다. */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final UserService userService;

    // 쿠키 속성. dev(http localhost)는 SameSite=Lax·Secure off로 동작하고, 운영(https)에서는
    // env로 SameSite=None·Secure=true로 올린다. Max-Age는 refresh 토큰 유효기간과 맞춘다.
    @Value("${app.auth.refresh-cookie.secure:false}")
    private boolean refreshCookieSecure;

    @Value("${app.auth.refresh-cookie.same-site:Lax}")
    private String refreshCookieSameSite;

    @Value("${app.jwt.refresh-token-validity-seconds:1209600}")
    private long refreshTokenValiditySeconds;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /** refresh 토큰을 담은 HttpOnly 쿠키를 만든다. maxAgeSeconds=0이면 삭제용 쿠키다. */
    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite(refreshCookieSameSite)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserSummary>> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(UserSummary.from(user)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        // refresh 토큰은 HttpOnly 쿠키로만 내리고, 응답 body에서는 뺀다(JS 노출 차단).
        ResponseCookie cookie = refreshCookie(response.refreshToken(), refreshTokenValiditySeconds);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(response.withoutRefreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        // refresh 토큰은 요청 body가 아니라 HttpOnly 쿠키에서 읽는다. 쿠키가 없으면(F5 후 미로그인 등)
        // 무효로 본다 — 프론트가 이 401을 받아 로그아웃 상태로 처리한다.
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(refreshToken)));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        String token = authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : authorizationHeader;
        authService.logout(token);
        // refresh 쿠키도 즉시 만료시킨다.
        ResponseCookie cleared = refreshCookie("", 0);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(ApiResponse.ok(null));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<UserSummary>> me(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.ok(authService.me(userDetails)));
    }

    @PostMapping("/users/{userId}/approve")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "가입 승인", description = "승인 대기(PENDING) 계정을 로그인 가능 상태로 전환합니다.")
    public ResponseEntity<ApiResponse<UserSummary>> approve(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(UserSummary.from(userService.approve(userId))));
    }
}
