package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.ProbeController;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingIT extends AbstractIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private static final String FEED_PATH = "/json/feed.xml";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private FeedPoller poller;

    private long createThenBreakFeed() {
        stub.serve(FEED_PATH, 200, "application/rss+xml", Fixtures.emptyRss("https://json.example.test"));
        long feedId = feedService.create(new CreateFeedRequest(stub.baseUrl() + FEED_PATH, null, Topic.NEWS, null)).id();
        stub.serve(FEED_PATH, 503, "text/plain", FeedStubServer.utf8("busy"));
        return feedId;
    }

    private List<JsonNode> jsonLinesContaining(CapturedOutput output, String text) {
        return Arrays.stream(output.getOut().split("\n"))
                .filter(line -> line.startsWith("{") && line.contains(text))
                .map(mapper::readTree)
                .toList();
    }

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

    @Test
    void aKeyValueFieldIsATopLevelJsonFieldNextToTheMdcFields(CapturedOutput output) {
        long feedId = createThenBreakFeed();

        ingestService.refresh(feedId);

        JsonNode warn = jsonLinesContaining(output, "Ingest failed for feed").getFirst();
        assertThat(warn.path("level").asString()).isEqualTo("WARN");
        assertThat(warn.path("reason").asString()).isEqualTo("http_status");
        assertThat(warn.path("httpStatus").asInt()).isEqualTo(503);
        assertThat(warn.path("errorType").asString()).isEqualTo("HttpStatus");
        assertThat(warn.path("consecutiveFailures").asInt()).isOne();
        assertThat(warn.path("url").asString()).isEqualTo(stub.baseUrl() + FEED_PATH);
        assertThat(warn.path("feedId").asString()).isEqualTo(String.valueOf(feedId));
    }

    @Test
    void theFailedFeedIdsOfAPollAreAJsonArray(CapturedOutput output) {
        long feedId = createThenBreakFeed();

        poller.poll(PollTrigger.MANUAL);

        JsonNode completed = jsonLinesContaining(output, "Poll manual completed").getFirst();
        assertThat(completed.path("failedFeedIds").isArray()).isTrue();
        assertThat(completed.path("failedFeedIds").get(0).asLong()).isEqualTo(feedId);
    }
}
