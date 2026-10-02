package com.j11a.argus.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Whether the current request carried a valid admin key. */
public final class AdminAccess {

    private AdminAccess() {
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AdminKeyFilter.ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }
}
