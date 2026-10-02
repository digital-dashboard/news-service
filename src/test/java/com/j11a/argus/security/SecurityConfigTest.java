package com.j11a.argus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.testsupport.ProbeController;
import com.j11a.argus.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void getNeedsNoCredentials() throws Exception {
        mockMvc.perform(get("/news/v2/probe")).andExpect(status().isOk());
    }

    @Test
    void postNeedsNeitherCredentialsNorCsrfToken() throws Exception {
        mockMvc.perform(post("/news/v2/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isOk());
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
