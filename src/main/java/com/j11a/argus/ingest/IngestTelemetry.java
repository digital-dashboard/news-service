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
import io.micrometer.core.instrument.Timer;
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
 * uncatalogued timers. Feed and source ids are span attributes only, never meter tags; the feed-health gauges, which
 * carry a feed_id tag, are the one exception. The argus.poll timer lives in PollingTelemetry.
 */
@Component
public class IngestTelemetry {

    static final String NO_REASON = "none";
    static final String NOT_MODIFIED = "not_modified";
    private static final String UNKNOWN_SOURCE = "unknown";
    private static final String NO_ERROR = "none";
    private static final String ERROR_TAG = "error";
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
            outcome = switch (report.outcome()) {
                case COMPLETED -> COMPLETED;
                case NOT_MODIFIED -> NOT_MODIFIED;
                case FAILED -> FAILED;
            };
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
                recordFetchSize(sourceKey, fetched.body().length);
                finishFetch(observation, FETCHED, NO_REASON);
            }
            case FetchResult.NotModified ignored -> finishFetch(observation, NOT_MODIFIED, NO_REASON);
            case FetchResult.Failed failed -> finishFetch(observation, FAILED, failed.reason().tag());
        }
        return result;
    }

    CreateFetchTimer startCreateFetch() {
        return new CreateFetchTimer();
    }

    /**
     * Times the create-path fetch. The source is unknown until the feed is parsed and an Observation's tags must be
     * set before it stops, so this is a plain Timer.Sample; the error=none tag mirrors what Observation timers carry.
     */
    public final class CreateFetchTimer {

        private final Timer.Sample sample = Timer.start(meters);

        private CreateFetchTimer() {
        }

        public void completed(String sourceKey, int bodyLength) {
            stopFetchTimer(sample, sourceKey, FETCHED, NO_REASON);
            recordFetchSize(sourceKey, bodyLength);
        }

        void failed(String reason) {
            stopFetchTimer(sample, UNKNOWN_SOURCE, FAILED, reason);
        }

        void parseFailed() {
            stopFetchTimer(sample, UNKNOWN_SOURCE, FETCHED, NO_REASON);
        }
    }

    private void stopFetchTimer(Timer.Sample sample, String source, String outcome, String reason) {
        sample.stop(Timer.builder(MetricNames.FETCH)
                .tag(SOURCE, source)
                .tag(OUTCOME, outcome)
                .tag(REASON, reason)
                .tag(ERROR_TAG, NO_ERROR)
                .register(meters));
    }

    private void recordFetchSize(String source, int bytes) {
        DistributionSummary.builder(MetricNames.FETCH_SIZE)
                .baseUnit("bytes")
                .tag(SOURCE, source)
                .register(meters)
                .record(bytes);
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
