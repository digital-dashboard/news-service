package com.j11a.argus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.HandlerExceptionResolver;

class AdminKeyAuthenticationEntryPointTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final AdminKeyAuthenticationEntryPoint entryPoint = new AdminKeyAuthenticationEntryPoint(resolver);

    private ApiException delegated() {
        ArgumentCaptor<Exception> captured = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), isNull(), captured.capture());
        return (ApiException) captured.getValue();
    }

    @Test
    void missingAuthenticationDelegatesAnAdminKeyRequiredProblem() {
        entryPoint.commence(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new BadCredentialsException("x"));

        assertThat(delegated().code()).isEqualTo(ErrorCode.ADMIN_KEY_REQUIRED);
    }

    @Test
    void accessDeniedDelegatesTheSameProblem() {
        entryPoint.handle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new AccessDeniedException("x"));

        assertThat(delegated().code()).isEqualTo(ErrorCode.ADMIN_KEY_REQUIRED);
        assertThat(delegated().getMessage()).isEqualTo("A valid X-Admin-Key header is required.");
    }
}
