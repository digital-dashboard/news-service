package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TraceExportIT extends AbstractIntegrationTest {

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private OtlpStubServer otlpStub;

    @Test
    void spansArePostedToTheTracesPathWithTheServiceName() throws Exception {
        mockMvc.perform(get("/news/v2/probe"));

        await().atMost(EXPORT_TIMEOUT).untilAsserted(() -> {
            List<OtlpStubServer.Request> requests = otlpStub.requests();
            assertThat(requests).isNotEmpty();
            assertThat(requests).allSatisfy(request -> assertThat(request.path()).isEqualTo("/v1/traces"));
            assertThat(requests).anySatisfy(request -> {
                assertThat(request.contentType()).isEqualTo("application/x-protobuf");
                String body = new String(request.body(), StandardCharsets.ISO_8859_1);
                assertThat(body).contains("service.name").contains("argus");
            });
        });
    }
}
