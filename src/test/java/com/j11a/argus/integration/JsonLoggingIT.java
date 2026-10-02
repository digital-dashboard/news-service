package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.j11a.argus.testsupport.ProbeController;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingIT extends AbstractIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void requestLogLineIsJsonCarryingTheIncomingTraceId(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/news/v2/probe").header("traceparent", TRACEPARENT));

        String line = Arrays.stream(output.getOut().split("\n"))
                .filter(candidate -> candidate.contains(ProbeController.LOG_MARKER))
                .findFirst()
                .orElseThrow();
        JsonNode json = mapper.readTree(line);

        assertThat(json.path("@timestamp").asString()).isNotBlank();
        assertThat(json.path("level").asString()).isEqualTo("INFO");
        assertThat(json.path("logger_name").asString()).isEqualTo(ProbeController.class.getName());
        assertThat(json.path("message").asString()).isEqualTo(ProbeController.LOG_MARKER);
        assertThat(json.path("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(json.path("spanId").asString()).matches("[0-9a-f]{16}");
    }
}
