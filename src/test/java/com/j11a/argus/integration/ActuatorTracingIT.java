package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActuatorTracingIT extends AbstractIntegrationTest {

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> ACTUATOR_PATHS = List.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/prometheus");

    @Autowired
    private OtlpStubServer otlpStub;

    private List<String> exportedBodies() {
        return otlpStub.requests().stream()
                .map(request -> new String(request.body(), StandardCharsets.ISO_8859_1))
                .toList();
    }

    @Test
    void actuatorRequestsExportNoSpansWhileApiRequestsDo() throws Exception {
        for (String path : ACTUATOR_PATHS) {
            mockMvc.perform(get(path));
        }
        mockMvc.perform(get("/news/v2/probe"));

        await().atMost(EXPORT_TIMEOUT).untilAsserted(() ->
                assertThat(exportedBodies()).anySatisfy(body -> assertThat(body).contains("http get")));
        String everything = String.join("\n", exportedBodies());
        assertThat(everything).doesNotContain("/actuator").doesNotContain("security filterchain");
    }
}
