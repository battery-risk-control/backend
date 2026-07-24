package com.example.batteryrisk.controller;

import com.example.batteryrisk.domain.User;
import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.auth.LoginRequest;
import com.example.batteryrisk.dto.auth.LoginResponse;
import com.example.batteryrisk.dto.auth.RefreshRequest;
import com.example.batteryrisk.dto.auth.RefreshResponse;
import com.example.batteryrisk.dto.auth.SignupRequest;
import com.example.batteryrisk.dto.auth.UserSummary;
import com.example.batteryrisk.security.CustomUserDetails;
import com.example.batteryrisk.service.AuthService;
import com.example.batteryrisk.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserSummary>> signup(@Valid @RequestBody SignupRequest request) {
        User user = userService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(UserSummary.from(user)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        String token = authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : authorizationHeader;
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.ok(null));
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
