package com.j11a.argus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.ProbeController;
import com.j11a.argus.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigTest {

    private static final String PROBE = "/news/v2/probe";
    private static final String BODY = "{\"name\":\"x\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void getNeedsNoCredentials() throws Exception {
        mockMvc.perform(get("/news/v2/probe")).andExpect(status().isOk());
    }

    static Stream<Arguments> writeRequests() {
        return Stream.of(
                Arguments.of("POST", post(PROBE)),
                Arguments.of("PUT", put(PROBE)),
                Arguments.of("PATCH", patch(PROBE)),
                Arguments.of("DELETE", delete(PROBE)));
    }

    @ParameterizedTest(name = "{0} without a key is 401 ADMIN_KEY_REQUIRED")
    @MethodSource("writeRequests")
    void writeWithoutKeyIsRejected(String method, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} with a wrong key is 401")
    @MethodSource("writeRequests")
    void writeWithWrongKeyIsRejected(String method, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(AdminKeys.HEADER, AdminKeys.VALID + "x")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} with a wrong key of another length is 401")
    @MethodSource("writeRequests")
    void writeWithShorterWrongKeyIsRejected(String method, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(AdminKeys.HEADER, "short")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} with the right key is let through")
    @MethodSource("writeRequests")
    void writeWithRightKeyPassesSecurity(String method, MockHttpServletRequestBuilder request) throws Exception {
        int status = mockMvc.perform(request.header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).isIn(200, 405);
    }

    @Test
    void writeToAnUnknownRouteWithoutKeyIsStill401() throws Exception {
        mockMvc.perform(post("/news/v2/nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithRightKeyReachesTheController() throws Exception {
        mockMvc.perform(post(PROBE).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void securityExceptionsThrownByAControllerAreNotTurnedInto500() throws Exception {
        mockMvc.perform(get(PROBE + "/denied"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @Test
    void optionsNeedsNoCredentials() throws Exception {
        mockMvc.perform(options("/news/v2/probe")).andExpect(status().isOk());
    }

    @Test
    void noSessionIsCreated() throws Exception {
        boolean sessionCreated = mockMvc.perform(get("/news/v2/probe")).andReturn().getRequest()
                .getSession(false) != null;

        assertThat(sessionCreated).isFalse();
    }

    @Test
    void noGeneratedPasswordIsLogged(CapturedOutput output) {
        assertThat(userDetailsService).isNotNull();
        assertThat(output).doesNotContain("generated security password");
    }
}
