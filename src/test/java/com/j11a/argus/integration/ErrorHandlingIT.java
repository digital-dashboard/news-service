package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ErrorHandlingIT extends AbstractIntegrationTest {

    @Test
    void unknownRouteIsAProblemResponseThroughTheFullStack() throws Exception {
        mockMvc.perform(get("/news/v2/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unmatchedRoutesAreRecordedUnderTheNotFoundUriTag() throws Exception {
        mockMvc.perform(get("/news/v2/nope"));

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();

        assertThat(scrape).contains("uri=\"NOT_FOUND\"");
    }
}
