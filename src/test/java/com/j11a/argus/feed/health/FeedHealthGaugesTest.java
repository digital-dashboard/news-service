package com.j11a.argus.feed.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.testsupport.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FeedHealthGaugesTest {

    private static final int FAILING_THRESHOLD = 3;

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private FeedHealthGauges gauges;

    @BeforeEach
    void setUp() {
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("database down"));
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);
        gauges = new FeedHealthGauges(jdbc, FAILING_THRESHOLD, clock, new SimpleMeterRegistry());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRefreshFailureIsLoggedAtWarnAndNeverReachesTheCaller() {
        try (LogCapture logs = LogCapture.start()) {
            gauges.refresh();

            assertThat(logs.at(Level.ERROR)).isEmpty();
            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).contains("gauge refresh failed"));
        }
    }

    @Test
    void outsideATransactionRefreshAfterCommitRefreshesAtOnce() {
        gauges.refreshAfterCommit();

        verify(jdbc).sql(anyString());
    }

    @Test
    void insideATransactionRefreshAfterCommitWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        gauges.refreshAfterCommit();

        verify(jdbc, never()).sql(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(jdbc).sql(anyString());
    }
}
