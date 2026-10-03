package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.testsupport.AdminKeys;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class IngestObservabilityIT extends AbstractIntegrationTest {

    private static final String PATH = "/observability/feed.xml";
    private static final AttributeKey<String> FEED_ID = AttributeKey.stringKey("feed.id");
    private static final AttributeKey<String> SOURCE_ID = AttributeKey.stringKey("source.id");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private SpanCollectorConfig.CollectingSpanProcessor spans;

    private FeedResponse create() {
        return createFeedFrom(PATH, "bbc-like-rss2.xml", Topic.WORLD);
    }

    private SpanData named(List<SpanData> trace, String name) {
        return trace.stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name + " in " + trace.stream()
                        .map(SpanData::getName).toList()));
    }

    private List<SpanData> traceOfIngest(long feedId) {
        SpanData ingest = spans.spans().stream()
                .filter(span -> span.getName().equals("argus.ingest"))
                .filter(span -> String.valueOf(feedId).equals(span.getAttributes().get(FEED_ID)))
                .reduce((first, second) -> second)
                .orElseThrow();
        return spans.spans().stream().filter(span -> span.getTraceId().equals(ingest.getTraceId())).toList();
    }

    @Test
    void aRefreshIsOneTraceWithIngestFetchParsePersistAndTheHttpClientSpan() {
        FeedResponse feed = create();

        ingestService.refresh(feed.id());

        List<SpanData> trace = traceOfIngest(feed.id());
        SpanData ingest = named(trace, "argus.ingest");
        SpanData fetch = named(trace, "argus.fetch");
        SpanData parse = named(trace, "argus.parse");
        SpanData persist = named(trace, "argus.persist");
        SpanData client = trace.stream().filter(span -> span.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
        assertThat(fetch.getParentSpanId()).isEqualTo(ingest.getSpanId());
        assertThat(parse.getParentSpanId()).isEqualTo(ingest.getSpanId());
        assertThat(persist.getParentSpanId()).isEqualTo(ingest.getSpanId());
        assertThat(client.getParentSpanId()).isEqualTo(fetch.getSpanId());
        assertThat(ingest.getAttributes().get(SOURCE_ID)).isNotBlank();
    }

    @Test
    void theClientSpanNeverCarriesTheFeedQueryString() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH + "?token=hunter2", null, Topic.WORLD, null));

        assertThat(spans.spans()).allSatisfy(span ->
                assertThat(span.getAttributes().asMap().values().toString()).doesNotContain("hunter2"));
    }

    @Test
    void anIngestOverHttpIsParentedByTheServerSpan() throws Exception {
        FeedResponse feed = create();

        mockMvc.perform(post("/news/v2/feeds/" + feed.id() + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID));

        List<SpanData> trace = traceOfIngest(feed.id());
        SpanData ingest = named(trace, "argus.ingest");
        SpanData parent = trace.stream().filter(span -> span.getSpanId().equals(ingest.getParentSpanId()))
                .findFirst().orElseThrow();
        assertThat(parent.getKind()).isEqualTo(SpanKind.SERVER);
    }

    @Test
    void theIngestSummaryLogLineCarriesFeedIdAndSourceId(CapturedOutput output) {
        FeedResponse feed = create();

        ingestService.refresh(feed.id());

        JsonNode line = Arrays.stream(output.getOut().split("\n"))
                .filter(candidate -> candidate.contains("\"message\":\"Ingest COMPLETED"))
                .map(this::parse)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(line.path("feedId").asString()).isEqualTo(String.valueOf(feed.id()));
        assertThat(line.path("sourceId").asString()).isEqualTo(String.valueOf(feed.source().id()));
        assertThat(line.path("message").asString()).contains("inserted=0").contains("unchanged=2");
    }

    @Test
    void theMdcDoesNotLeakOutOfAnIngest() {
        FeedResponse feed = create();

        ingestService.refresh(feed.id());

        assertThat(MDC.get("feedId")).isNull();
        assertThat(MDC.get("sourceId")).isNull();
    }

    private JsonNode parse(String line) {
        return mapper.readTree(line);
    }
}
