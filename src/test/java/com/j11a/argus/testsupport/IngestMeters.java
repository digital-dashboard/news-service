package com.j11a.argus.testsupport;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Reads ingest meters as plain numbers; a meter that does not exist yet counts as zero. */
public final class IngestMeters {

    private final MeterRegistry registry;

    public IngestMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    public double entries(String source, String decision, String reason) {
        return count(MetricNames.INGEST_ENTRIES, "source", source, "decision", decision, "reason", reason);
    }

    /** Sum over every reason, for a decision whose reason is not under test. */
    public double entries(String decision) {
        return registry.find(MetricNames.INGEST_ENTRIES).tag("decision", decision).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    public double fallback(String source, String outcome) {
        return count(MetricNames.INGEST_LINK_FALLBACK, "source", source, "outcome", outcome);
    }

    public long lockWaits(String source) {
        Timer timer = registry.find(MetricNames.INGEST_LOCK_WAIT).tag("source", source).timer();
        return timer == null ? 0 : timer.count();
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}
