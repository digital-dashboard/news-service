package com.j11a.argus.feed.health;

import com.j11a.argus.config.PollProperties;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class FeedHealthGauges {

    private static final String STATE_HEALTHY = "healthy";
    private static final String STATE_FAILING = "failing";
    private static final String STATE_DISABLED = "disabled";

    private final JdbcClient jdbc;
    private final PollProperties properties;
    private final Clock clock;
    private final MultiGauge stateGauge;
    private final MultiGauge consecutiveFailuresGauge;
    private final MultiGauge sinceLastSuccessGauge;

    public FeedHealthGauges(JdbcClient jdbc, PollProperties properties, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.stateGauge = MultiGauge.builder(MetricNames.FEED_STATE)
                .description("Current operational state of each feed (1 for active state)")
                .register(registry);
        this.consecutiveFailuresGauge = MultiGauge.builder(MetricNames.FEED_CONSECUTIVE_FAILURES)
                .description("Number of consecutive fetch or ingest failures for each feed")
                .register(registry);
        this.sinceLastSuccessGauge = MultiGauge.builder(MetricNames.FEED_SINCE_LAST_SUCCESS)
                .description("Seconds elapsed since the last successful ingest for each feed, or -1 if never")
                .baseUnit("seconds")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refresh();
    }

    public synchronized void refresh() {
        List<FeedSnapshot> snapshots = jdbc.sql("""
                SELECT id, enabled, consecutive_failures, last_success_at
                FROM feed
                """)
                .query((rs, rowNum) -> {
                    long id = rs.getLong("id");
                    boolean enabled = rs.getBoolean("enabled");
                    int consecutiveFailures = rs.getInt("consecutive_failures");
                    OffsetDateTime lastSuccess = rs.getObject("last_success_at", OffsetDateTime.class);
                    return new FeedSnapshot(id, enabled, consecutiveFailures,
                            lastSuccess != null ? lastSuccess.toInstant() : null);
                })
                .list();

        List<MultiGauge.Row<?>> stateRows = new ArrayList<>(snapshots.size());
        List<MultiGauge.Row<?>> consecutiveRows = new ArrayList<>(snapshots.size());
        List<MultiGauge.Row<?>> sinceLastSuccessRows = new ArrayList<>(snapshots.size());

        for (FeedSnapshot snapshot : snapshots) {
            String feedIdStr = String.valueOf(snapshot.id());
            String state = resolveState(snapshot);

            stateRows.add(MultiGauge.Row.of(
                    Tags.of(MetricNames.Tags.FEED_ID, feedIdStr, MetricNames.Tags.STATE, state),
                    1.0));
            consecutiveRows.add(MultiGauge.Row.of(
                    Tags.of(MetricNames.Tags.FEED_ID, feedIdStr),
                    snapshot,
                    s -> (double) s.consecutiveFailures()));
            sinceLastSuccessRows.add(MultiGauge.Row.of(
                    Tags.of(MetricNames.Tags.FEED_ID, feedIdStr),
                    snapshot,
                    s -> s.lastSuccessAt() == null ? -1.0 : Math.max(0.0,
                            (double) Duration.between(s.lastSuccessAt(), clock.instant()).toSeconds())));
        }

        stateGauge.register(stateRows, true);
        consecutiveFailuresGauge.register(consecutiveRows, true);
        sinceLastSuccessGauge.register(sinceLastSuccessRows, true);
    }

    private String resolveState(FeedSnapshot snapshot) {
        if (!snapshot.enabled()) {
            return STATE_DISABLED;
        }
        if (snapshot.consecutiveFailures() >= properties.failingThreshold()) {
            return STATE_FAILING;
        }
        return STATE_HEALTHY;
    }

    private record FeedSnapshot(long id, boolean enabled, int consecutiveFailures, @Nullable Instant lastSuccessAt) {
    }
}
