package com.j11a.argus.ingest;

import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.Source;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import com.j11a.argus.ingest.dedup.LinkFallback;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestTelemetryTest {

    private static final String SOURCE_KEY = "example.test";
    private static final Instant WHEN = Instant.parse("2026-10-02T10:00:00Z");

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final IngestTelemetry telemetry;

    IngestTelemetryTest() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        telemetry = new IngestTelemetry(observations, meters, Tracer.NOOP);
    }

    private static ParsedEntry entry(String guid, String author, String image, Instant published, Instant updated) {
        return new ParsedEntry(guid, null, "t", "e", author, image, List.of(), published, updated);
    }

    private double missing(String kind) {
        Counter counter = meters.find(MetricNames.PARSE_MISSING).tag(SOURCE, SOURCE_KEY).tag(KIND, kind).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aFetchThatThrowsIsRecordedAsAnIoFailureAndTheExceptionPropagates() {
        assertThatThrownBy(() -> telemetry.fetch(SOURCE_KEY, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        Timer timer = meters.find(MetricNames.FETCH)
                .tag(SOURCE, SOURCE_KEY).tag(OUTCOME, "failed").tag(REASON, "io").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void missingDataIsCountedPerKindAndAnEntryWithAnUpdateDateHasADate() {
        List<ParsedEntry> entries = List.of(
                entry("g1", "Ann", "https://example.test/i.jpg", null, WHEN),
                entry(null, null, null, null, null),
                entry("g3", null, "https://example.test/j.jpg", WHEN, null));

        telemetry.recordMissing(SOURCE_KEY, entries);

        assertThat(missing("date")).isEqualTo(1);
        assertThat(missing("guid")).isEqualTo(1);
        assertThat(missing("image")).isEqualTo(1);
        assertThat(missing("author")).isEqualTo(2);
    }

    @Test
    void completeEntriesRecordNoMissingCounters() {
        telemetry.recordMissing(SOURCE_KEY, List.of(entry("g", "Ann", "https://example.test/i.jpg", WHEN, WHEN)));

        assertThat(meters.find(MetricNames.PARSE_MISSING).counters()).isEmpty();
    }

    private double ingestOutcomeCount(String outcome) {
        Timer timer = meters.find(MetricNames.INGEST).tag(OUTCOME, outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void anIngestOutcomeIsTaggedWithTheReportOutcome() {
        Feed feed = feed();

        telemetry.ingest(feed, () -> IngestReport.notModified(1L));
        telemetry.ingest(feed, () -> IngestReport.failed(1L, "io"));
        telemetry.ingest(feed, () -> IngestReport.completed(1L, 0,
                new PersistCounts(0, Map.of(), 0, 0, Map.of(), Map.of())));

        assertThat(ingestOutcomeCount("not_modified")).isEqualTo(1);
        assertThat(ingestOutcomeCount("failed")).isEqualTo(1);
        assertThat(ingestOutcomeCount("completed")).isEqualTo(1);
    }

    @Test
    void recordDecisionsEmitsCountersOnlyWhenCountIsPositive() {
        PersistCounts counts = new PersistCounts(
                2,
                Map.of("content_changed", 1, "timestamp_only", 0),
                3,
                0,
                Map.of("missing_identity", 4, "batch_duplicate", 0),
                Map.of());

        telemetry.recordDecisions(SOURCE_KEY, counts);

        assertThat(decisionCount("inserted", "none")).isEqualTo(2);
        assertThat(decisionCount("updated", "content_changed")).isEqualTo(1);
        assertThat(decisionCount("updated", "timestamp_only")).isEqualTo(0);
        assertThat(decisionCount("linked", "none")).isEqualTo(3);
        assertThat(decisionCount("unchanged", "none")).isEqualTo(0);
        assertThat(decisionCount("skipped", "missing_identity")).isEqualTo(4);
        assertThat(decisionCount("skipped", "batch_duplicate")).isEqualTo(0);
    }

    private double decisionCount(String decision, String reason) {
        Counter counter = meters.find(MetricNames.INGEST_ENTRIES)
                .tag(SOURCE, SOURCE_KEY)
                .tag("decision", decision)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static Feed feed() {
        Feed feed = mock(Feed.class);
        Source source = mock(Source.class);
        when(source.getKey()).thenReturn(SOURCE_KEY);
        when(source.getId()).thenReturn(2L);
        when(feed.getId()).thenReturn(1L);
        when(feed.getSource()).thenReturn(source);
        return feed;
    }

    private double fetchTimerCount(String source, String outcome, String reason) {
        Timer timer = meters.find(MetricNames.FETCH)
                .tag(SOURCE, source).tag(OUTCOME, outcome).tag(REASON, reason).tag("error", "none").timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aCompletedCreateFetchIsTimedUnderTheResolvedSourceAndRecordsItsSize() {
        telemetry.startCreateFetch().completed(SOURCE_KEY, 120);

        assertThat(fetchTimerCount(SOURCE_KEY, "fetched", "none")).isEqualTo(1);
        DistributionSummary size = meters.find(MetricNames.FETCH_SIZE).tag(SOURCE, SOURCE_KEY).summary();
        assertThat(size).isNotNull();
        assertThat(size.totalAmount()).isEqualTo(120);
    }

    @Test
    void aFailedCreateFetchOrParseIsTimedUnderTheUnknownSource() {
        telemetry.startCreateFetch().failed("timeout");
        telemetry.startCreateFetch().parseFailed();

        assertThat(fetchTimerCount("unknown", "failed", "timeout")).isEqualTo(1);
        assertThat(fetchTimerCount("unknown", "fetched", "none")).isEqualTo(1);
    }

    @Test
    void recordLinkFallbacksIncrementsAllThreeOutcomesEvenWhenZero() {
        Map<LinkFallback, Integer> fallbacks = Map.of(
                LinkFallback.GUID_REPLACED, 2,
                LinkFallback.GUARDED_HOMEPAGE, 0,
                LinkFallback.GUARDED_SHARED, 1);

        telemetry.recordLinkFallbacks(SOURCE_KEY, fallbacks);

        assertThat(meters.find(MetricNames.INGEST_LINK_FALLBACK)
                .tag(SOURCE, SOURCE_KEY).tag(OUTCOME, "guid_replaced").counter().count()).isEqualTo(2.0);
        assertThat(meters.find(MetricNames.INGEST_LINK_FALLBACK)
                .tag(SOURCE, SOURCE_KEY).tag(OUTCOME, "guarded_homepage").counter().count()).isEqualTo(0.0);
        assertThat(meters.find(MetricNames.INGEST_LINK_FALLBACK)
                .tag(SOURCE, SOURCE_KEY).tag(OUTCOME, "guarded_shared").counter().count()).isEqualTo(1.0);
    }

    @Test
    void lockWaitWrapsAcquisitionAndRecordsTimer() {
        Duration wait = telemetry.lockWait(SOURCE_KEY, 42L, () -> Duration.ofMillis(15));
        assertThat(wait).isEqualTo(Duration.ofMillis(15));

        Timer timer = meters.find(MetricNames.INGEST_LOCK_WAIT).tag(SOURCE, SOURCE_KEY).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void spanExecutesActionAndReturnsResult() {
        String result = telemetry.span("test.span", Map.of("source.id", "42"), () -> "hello");
        assertThat(result).isEqualTo("hello");
    }
}
