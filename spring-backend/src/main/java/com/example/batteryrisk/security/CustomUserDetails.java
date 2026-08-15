package com.example.batteryrisk.security;

import com.example.batteryrisk.domain.Role;
import com.example.batteryrisk.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.List;

public class CustomUserDetails implements UserDetails {

    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    public Long getId() {
        return user.getId();
    }

    public String getName() {
        return user.getName();
    }

    public String getRole() {
        return user.getRole().name();
    }

    public User getUser() {
        return user;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        // 시연용 마스터 계정은 1·2·3계층(구매/기획/임원)과 관리자 화면을 한 계정으로 모두 열람할 수
        // 있어야 한다. 여기서 모든 역할 권한을 부여하면 각 컨트롤러의 @PreAuthorize(hasAnyRole/hasRole)를
        // 한 줄도 고치지 않고 마스터가 통과한다. MASTER는 test-seed에서만 생성된다(AuthTestSeedConfig).
        if (user.getRole() == Role.MASTER) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_PURCHASING"),
                    new SimpleGrantedAuthority("ROLE_STRATEGY"),
                    new SimpleGrantedAuthority("ROLE_EXECUTIVE"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_MASTER"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // 계정 잠금 심층 방어. AuthService.login이 앞단에서 이미 검사하지만,
        // DaoAuthenticationProvider 레벨에서도 잠긴 계정의 비밀번호 검증을 막는다.
        return !user.isLocked(OffsetDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }
}
