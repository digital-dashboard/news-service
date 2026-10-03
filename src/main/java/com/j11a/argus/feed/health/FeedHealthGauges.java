package com.j11a.argus.feed.health;

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
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registered as a bean by ArgusConfiguration, which supplies the failing threshold; this package must not depend on
 * the poll package.
 */
@Slf4j
public class FeedHealthGauges {

    // A lock, not synchronized: JDBC under a monitor would pin a virtual thread to its carrier.
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final JdbcClient jdbc;
    private final int failingThreshold;
    private final Clock clock;
    private final MultiGauge stateGauge;
    private final MultiGauge consecutiveFailuresGauge;
    private final MultiGauge sinceLastSuccessGauge;

    public FeedHealthGauges(JdbcClient jdbc, int failingThreshold, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.failingThreshold = failingThreshold;
        this.clock = clock;
        this.stateGauge = MultiGauge.builder(MetricNames.FEED_STATE)
                .description("Current operational state of each feed (1 for active state)")
                .register(registry);
        this.consecutiveFailuresGauge = MultiGauge.builder(MetricNames.FEED_CONSECUTIVE_FAILURES)
                .description("Number of consecutive fetch or ingest failures for each feed")
                .register(registry);
        this.sinceLastSuccessGauge = MultiGauge.builder(MetricNames.FEED_SINCE_LAST_SUCCESS)
                .description("Seconds elapsed since the last successful fetch (including 304 Not Modified) for each feed, or -1 if never")
                .baseUnit("seconds")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refresh();
    }

    /** Re-reads the feed rows now. A failure is logged and never reaches the caller: gauges are best effort. */
    public void refresh() {
        refreshLock.lock();
        try {
            registerRows(readSnapshots());
        } catch (RuntimeException e) {
            log.warn("Feed health gauge refresh failed; the gauges keep their previous rows", e);
        } finally {
            refreshLock.unlock();
        }
    }

    /** Inside a transaction the rows are only visible after commit, so the refresh waits for it. */
    public void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refresh();
            }
        });
    }

    private List<FeedSnapshot> readSnapshots() {
        return jdbc.sql("""
                SELECT id, enabled, consecutive_failures, last_success_at
                FROM feed
                """)
                .query((rs, rowNum) -> {
                    OffsetDateTime lastSuccess = rs.getObject("last_success_at", OffsetDateTime.class);
                    return new FeedSnapshot(rs.getLong("id"), rs.getBoolean("enabled"),
                            rs.getInt("consecutive_failures"), lastSuccess != null ? lastSuccess.toInstant() : null);
                })
                .list();
    }

    private void registerRows(List<FeedSnapshot> snapshots) {
        List<MultiGauge.Row<?>> stateRows = new ArrayList<>(snapshots.size());
        List<MultiGauge.Row<?>> consecutiveRows = new ArrayList<>(snapshots.size());
        List<MultiGauge.Row<?>> sinceLastSuccessRows = new ArrayList<>(snapshots.size());

        for (FeedSnapshot snapshot : snapshots) {
            String feedIdStr = String.valueOf(snapshot.id());
            String state = FeedState.of(snapshot.enabled(), snapshot.consecutiveFailures(), failingThreshold).tag();

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
                            Duration.between(s.lastSuccessAt(), clock.instant()).toSeconds())));
        }

        stateGauge.register(stateRows, true);
        consecutiveFailuresGauge.register(consecutiveRows, true);
        sinceLastSuccessGauge.register(sinceLastSuccessRows, true);
    }

    private record FeedSnapshot(long id, boolean enabled, int consecutiveFailures, @Nullable Instant lastSuccessAt) {
    }
}
