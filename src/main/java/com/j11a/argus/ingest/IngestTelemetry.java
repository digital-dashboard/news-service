package com.j11a.argus.ingest;

import static com.j11a.argus.observability.MetricNames.Tags.DECISION;
import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Observations (and so timers) exist only for ingest and fetch. Parse and persist are bare spans, so they add no
 * uncatalogued timers. Feed and source ids are span attributes only, never meter tags.
 */
@Component
public class IngestTelemetry {

    static final String NO_REASON = "none";
    private static final String COMPLETED = "completed";
    private static final String FAILED = "failed";
    private static final String FETCHED = "fetched";
    private static final String UNEXPECTED_FETCH_REASON = "io";
    private static final String DECISION_INSERTED = "inserted";
    private static final String DECISION_UNCHANGED = "unchanged";
    private static final String DECISION_SKIPPED = "skipped";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Tracer tracer;

    public IngestTelemetry(ObservationRegistry observations, MeterRegistry meters, Tracer tracer) {
        this.observations = observations;
        this.meters = meters;
        this.tracer = tracer;
    }

    IngestReport ingest(Feed feed, Supplier<IngestReport> work) {
        Observation observation = Observation.createNotStarted(MetricNames.INGEST, observations)
                .lowCardinalityKeyValue(SOURCE, feed.getSource().getKey())
                .highCardinalityKeyValue("feed.id", String.valueOf(feed.getId()))
                .highCardinalityKeyValue("source.id", String.valueOf(feed.getSource().getId()))
                .start();
        String outcome = FAILED;
        try (Observation.Scope ignored = observation.openScope()) {
            IngestReport report = work.get();
            outcome = report.outcome() == IngestReport.Outcome.COMPLETED ? COMPLETED : FAILED;
            return report;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.lowCardinalityKeyValue(OUTCOME, outcome);
            observation.stop();
        }
    }

    FetchResult fetch(String sourceKey, Supplier<FetchResult> call) {
        Observation observation = Observation.createNotStarted(MetricNames.FETCH, observations)
                .lowCardinalityKeyValue(SOURCE, sourceKey)
                .start();
        FetchResult result;
        try (Observation.Scope ignored = observation.openScope()) {
            result = call.get();
        } catch (RuntimeException e) {
            observation.error(e);
            finishFetch(observation, FAILED, UNEXPECTED_FETCH_REASON);
            throw e;
        }
        switch (result) {
            case FetchResult.Fetched fetched -> {
                DistributionSummary.builder(MetricNames.FETCH_SIZE)
                        .baseUnit("bytes")
                        .tag(SOURCE, sourceKey)
                        .register(meters)
                        .record(fetched.body().length);
                finishFetch(observation, FETCHED, NO_REASON);
            }
            case FetchResult.Failed failed -> finishFetch(observation, FAILED, failed.reason().tag());
        }
        return result;
    }

    private static void finishFetch(Observation observation, String outcome, String reason) {
        observation.lowCardinalityKeyValue(OUTCOME, outcome);
        observation.lowCardinalityKeyValue(REASON, reason);
        observation.stop();
    }

    <T> T span(String name, Supplier<T> work) {
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    void recordMissing(String sourceKey, List<ParsedEntry> entries) {
        countMissing(sourceKey, "date", entries, e -> e.publishedAt() == null && e.updatedAt() == null);
        countMissing(sourceKey, "guid", entries, e -> e.guid() == null);
        countMissing(sourceKey, "image", entries, e -> e.imageUrl() == null);
        countMissing(sourceKey, "author", entries, e -> e.author() == null);
    }

    private void countMissing(String sourceKey, String kind, List<ParsedEntry> entries, Predicate<ParsedEntry> missing) {
        long count = entries.stream().filter(missing).count();
        if (count > 0) {
            meters.counter(MetricNames.PARSE_MISSING, SOURCE, sourceKey, KIND, kind).increment(count);
        }
    }

    void recordDecisions(String sourceKey, PersistCounts counts) {
        countDecision(sourceKey, DECISION_INSERTED, NO_REASON, counts.inserted());
        countDecision(sourceKey, DECISION_UNCHANGED, NO_REASON, counts.unchanged());
        for (Map.Entry<String, Integer> skipped : counts.skipped().entrySet()) {
            countDecision(sourceKey, DECISION_SKIPPED, skipped.getKey(), skipped.getValue());
        }
    }

    private void countDecision(String sourceKey, String decision, String reason, int count) {
        if (count > 0) {
            meters.counter(MetricNames.INGEST_ENTRIES, SOURCE, sourceKey, DECISION, decision, REASON, reason)
                    .increment(count);
        }
    }
}
