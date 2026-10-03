package com.j11a.argus.testsupport;

import static com.j11a.argus.observability.MetricNames.Tags.DECISION;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.REASON;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/** Reads ingest meters as plain numbers; a meter that does not exist yet counts as zero. */
public final class IngestMeters {

    private final MeterRegistry registry;

    public IngestMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    public double entries(String source, String decision, String reason) {
        return counter(MetricNames.INGEST_ENTRIES, SOURCE, source, DECISION, decision, REASON, reason);
    }

    /** Sum over every reason, for a decision whose reason is not under test. */
    public double entries(String decision) {
        return registry.find(MetricNames.INGEST_ENTRIES).tag(DECISION, decision).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    public double fallback(String source, String outcome) {
        return counter(MetricNames.INGEST_LINK_FALLBACK, SOURCE, source, OUTCOME, outcome);
    }

    public long lockWaits(String source) {
        return timerCount(MetricNames.INGEST_LOCK_WAIT, SOURCE, source);
    }

    public double lockWaitSeconds(String source) {
        Timer timer = registry.find(MetricNames.INGEST_LOCK_WAIT).tag(SOURCE, source).timer();
        return timer == null ? 0 : timer.totalTime(TimeUnit.SECONDS);
    }

    public double lockWaitMaxNanos(String source) {
        Timer timer = registry.find(MetricNames.INGEST_LOCK_WAIT).tag(SOURCE, source).timer();
        return timer == null ? 0 : timer.max(TimeUnit.NANOSECONDS);
    }

    public double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    public long timerCount(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    public long summaryCount(String name, String... tags) {
        DistributionSummary summary = registry.find(name).tags(tags).summary();
        return summary == null ? 0 : summary.count();
    }
}
