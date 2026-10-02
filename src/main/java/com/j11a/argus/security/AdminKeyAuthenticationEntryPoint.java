package com.j11a.argus.security;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Renders both security failures through the MVC exception resolvers, so the problem+json body comes from the same
 * handler as every other error.
 */
class AdminKeyAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String DETAIL = "A valid X-Admin-Key header is required.";

    private final HandlerExceptionResolver resolver;

    AdminKeyAuthenticationEntryPoint(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        reject(request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
        reject(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) {
        resolver.resolveException(request, response, null, new ApiException(ErrorCode.ADMIN_KEY_REQUIRED, DETAIL));
    }
}
