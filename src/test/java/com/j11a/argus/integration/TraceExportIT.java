package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.testsupport.MergeData;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TraceExportIT extends AbstractIntegrationTest {

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);

    private static final AttributeKey<String> SOURCE_ID = AttributeKey.stringKey("source.id");
    private static final AttributeKey<String> TARGET_SOURCE_ID = AttributeKey.stringKey("target.source.id");

    @Autowired
    private OtlpStubServer otlpStub;

    @Autowired
    private SpanCollectorConfig.CollectingSpanProcessor spans;

    @Autowired
    private SourceMerger merger;

    @Autowired
    private MeterRegistry registry;

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

    @Test
    void aMergeProducesASpanWithTheSourceIdsAsAttributesAndNeverAsMetricTags() {
        MergeData data = new MergeData(jdbcClient);
        long source = data.source("bbci.co.uk", null);
        long target = data.source("bbc.co.uk", null);

        merger.merge(source, target);

        SpanData span = spans.spans().stream().filter(candidate -> candidate.getName().equals(MetricNames.SOURCE_MERGE))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(span.getAttributes().get(SOURCE_ID)).isEqualTo(String.valueOf(source));
        assertThat(span.getAttributes().get(TARGET_SOURCE_ID)).isEqualTo(String.valueOf(target));
        assertThat(span.getAttributes().get(AttributeKey.stringKey("type"))).isEqualTo("merge");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("completed");
        List<Meter> mergeMeters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(MetricNames.SOURCE_MERGE)).toList();
        assertThat(mergeMeters).isNotEmpty().allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(Tag::getKey).doesNotContain("source.id", "target.source.id", "source_id", "feed.id"));
    }
}
