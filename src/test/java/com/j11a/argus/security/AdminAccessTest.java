package com.j11a.argus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminAccessTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void isFalseWithoutAnAuthentication() {
        assertThat(AdminAccess.isAdmin()).isFalse();
    }

    @Test
    void isFalseForAnAuthenticationWithoutTheAdminAuthority() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "anonymous", null, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(AdminAccess.isAdmin()).isFalse();
    }

    @Test
    void isTrueForTheAdminAuthority() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority(AdminKeyFilter.ADMIN_AUTHORITY))));

        assertThat(AdminAccess.isAdmin()).isTrue();
    }
}
