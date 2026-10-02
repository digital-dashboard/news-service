package com.j11a.argus.ingest;

import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
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
}
