package com.j11a.argus.feed.poll;

import com.j11a.argus.observability.LogKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Says once at startup how polling is configured and how many feeds it will poll. */
@Slf4j
@Component
public class PollingStartupLogger {

    private record FeedCounts(long enabled, long failing) {
    }

    private final JdbcClient jdbc;
    private final PollProperties properties;

    public PollingStartupLogger(JdbcClient jdbc, PollProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logPollingSchedule() {
        try {
            FeedCounts counts = jdbc.sql("""
                    SELECT count(*) FILTER (WHERE enabled) AS enabled,
                           count(*) FILTER (WHERE enabled AND consecutive_failures >= :threshold) AS failing
                    FROM feed
                    """)
                    .param("threshold", properties.failingThreshold())
                    .query((rs, rowNum) -> new FeedCounts(rs.getLong("enabled"), rs.getLong("failing")))
                    .single();
            log.atInfo()
                    .setMessage("Polling scheduled with cron " + properties.cron() + ", concurrency "
                            + properties.concurrency() + "; " + counts.enabled() + " enabled feeds, "
                            + counts.failing() + " failing")
                    .addKeyValue(LogKeys.CRON, properties.cron())
                    .addKeyValue(LogKeys.CONCURRENCY, properties.concurrency())
                    .addKeyValue(LogKeys.ENABLED_FEEDS, counts.enabled())
                    .addKeyValue(LogKeys.FAILING_FEEDS, counts.failing())
                    .log();
        } catch (RuntimeException e) {
            log.warn("Could not count feeds for the polling startup line", e);
        }
    }
}
