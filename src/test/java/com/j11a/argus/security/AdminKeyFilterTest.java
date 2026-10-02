package com.j11a.argus.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.testsupport.AdminKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(OutputCaptureExtension.class)
class AdminKeyFilterTest {

    private final AdminKeyFilter filter = new AdminKeyFilter(AdminKeys.VALID);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rightKeyAuthenticatesWithTheAdminAuthority() throws Exception {
        Authentication seen = run(AdminKeys.VALID);

        assertThat(seen).isNotNull();
        assertThat(seen.isAuthenticated()).isTrue();
        assertThat(seen.getAuthorities()).extracting("authority").containsExactly("ADMIN");
    }

    @Test
    void missingHeaderLeavesTheRequestUnauthenticated() throws Exception {
        assertThat(run(null)).isNull();
    }

    @Test
    void wrongKeyLeavesTheRequestUnauthenticated() throws Exception {
        assertThat(run(AdminKeys.VALID + "x")).isNull();
    }

    @Test
    void emptyKeyLeavesTheRequestUnauthenticated() throws Exception {
        assertThat(run("")).isNull();
    }

    @Test
    void keysOfAnotherLengthAreRejectedWithoutError() throws Exception {
        assertThat(run("k")).isNull();
        assertThat(run(AdminKeys.VALID.repeat(3))).isNull();
    }

    @Test
    void theKeyNeverAppearsInLogs(CapturedOutput output) throws Exception {
        run(AdminKeys.VALID);
        run("a-wrong-key-that-must-not-be-logged-either");

        assertThat(output).doesNotContain(AdminKeys.VALID).doesNotContain("a-wrong-key-that-must-not-be-logged");
    }

    private Authentication run(String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (key != null) {
            request.addHeader(AdminKeys.HEADER, key);
        }
        Authentication[] seen = new Authentication[1];
        FilterChain chain = (HttpServletRequest, response) ->
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        SecurityContextHolder.clearContext();
        return seen[0];
    }
}
