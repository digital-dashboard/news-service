package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

class PrometheusEndpointIT extends AbstractIntegrationTest {

    private static final String PROBE_TIMER = "argus.phase1.probe";

    @Autowired
    private MeterRegistry registry;

    private String scrape() throws Exception {
        mockMvc.perform(get("/news/v2/nope"));
        mockMvc.perform(get("/news/v2/probe"));
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    @Test
    void exposesHttpJvmHikariAndProcessSeriesWithoutCredentials() throws Exception {
        String body = scrape();

        assertThat(body).contains(
                "http_server_requests_seconds_bucket{",
                "jvm_memory_used_bytes",
                "jvm_threads_live_threads",
                "hikaricp_connections_active",
                "process_uptime_seconds");
    }

    @Test
    void everySeriesIsTaggedWithTheApplication() throws Exception {
        assertThat(scrape()).contains("application=\"argus\"");
    }

    @Test
    void argusTimersGetHistogramBuckets() throws Exception {
        Timer.builder(PROBE_TIMER).register(registry).record(Duration.ofMillis(5));

        assertThat(scrape()).contains("argus_phase1_probe_seconds_bucket");
    }

    @Test
    void actuatorRequestsAreNotObserved() throws Exception {
        assertThat(scrape()).doesNotContain("uri=\"/actuator");
    }

    @Test
    void otherActuatorEndpointsAreNotServed() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/metrics", "/actuator/beans"}) {
            int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

            assertThat(status).as(path).isNotEqualTo(200);
        }
    }
}
