package com.j11a.argus.feed.identity;

import static com.j11a.argus.observability.MetricNames.Tags.KIND;
import static com.j11a.argus.observability.MetricNames.Tags.OUTCOME;
import static com.j11a.argus.observability.MetricNames.Tags.SOURCE;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The conflict counter has a closed set of kinds, so every series exists at zero from startup. The redirect counter
 * carries the source key, which is only known at the time, so it is registered on first use.
 */
@Component
public class FeedIdentityTelemetry implements MeterBinder {

    static final String PERMANENT_APPLIED = "permanent_applied";
    static final String PERMANENT_CONFLICT = "permanent_conflict";

    private final MeterRegistry meters;

    public FeedIdentityTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (IdentityKind kind : IdentityKind.values()) {
            registry.counter(MetricNames.FEED_IDENTITY_CONFLICT, KIND, kind.tag());
        }
    }

    public void conflict(IdentityKind kind) {
        meters.counter(MetricNames.FEED_IDENTITY_CONFLICT, KIND, kind.tag()).increment();
    }

    void redirect(String sourceKey, String outcome) {
        meters.counter(MetricNames.FEED_REDIRECT, SOURCE, sourceKey, OUTCOME, outcome).increment();
    }
}
