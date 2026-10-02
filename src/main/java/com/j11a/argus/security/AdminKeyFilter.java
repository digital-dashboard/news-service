package com.j11a.argus.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Grants ADMIN when X-Admin-Key matches. Digests are compared so the comparison time does not depend on how much of
 * the key was right, nor on its length. Not a bean: Boot would register a bean filter in the servlet chain as well.
 */
public final class AdminKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Key";
    static final String ADMIN_AUTHORITY = "ADMIN";
    private static final String PRINCIPAL = "admin";

    private final SecurityContextHolderStrategy contextStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final byte[] expectedDigest;

    AdminKeyFilter(String expectedKey) {
        this.expectedDigest = sha256(expectedKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(sha256(given), expectedDigest)) {
            SecurityContext context = contextStrategy.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority(ADMIN_AUTHORITY))));
            contextStrategy.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JDK", e);
        }
    }
}
