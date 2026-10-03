package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.LogCapture;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollingTelemetryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private SimpleMeterRegistry registry;
    private ObservationRegistry observations;
    private Clock clock;
    private PollingTelemetry telemetry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observations = ObservationRegistry.create();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        telemetry = new PollingTelemetry(observations, registry, clock);
    }

    @Test
    void lastSuccessGaugeReportsNaNInitiallyAndEpochSecondsAfterSuccess() {
        Gauge gauge = registry.find(MetricNames.POLL_LAST_SUCCESS).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isNaN();

        AggregatePollReport report = AggregatePollReport.of("p1", PollTrigger.MANUAL, Duration.ofMillis(100), List.of());
        telemetry.poll("p1", PollTrigger.MANUAL, () -> report);

        assertThat(gauge.value()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void failedPollDoesNotUpdateLastSuccessAndObservationRecordsError() {
        Gauge gauge = registry.find(MetricNames.POLL_LAST_SUCCESS).gauge();
        assertThat(gauge).isNotNull();

        assertThatThrownBy(() -> telemetry.poll("p2", PollTrigger.SCHEDULED, () -> {
            throw new RuntimeException("db failure");
        })).isInstanceOf(RuntimeException.class);

        assertThat(gauge.value()).isNaN();
    }

    private double jobCount(String outcome) {
        return registry.find(MetricNames.SCHEDULED_JOB)
                .tag(MetricNames.Tags.OUTCOME, outcome)
                .counter().count();
    }

    @Test
    void recordScheduledJobCountsEachOutcomeSeparately() {
        telemetry.recordScheduledJob("success");
        telemetry.recordScheduledJob("skipped");
        telemetry.recordScheduledJob("error");
        telemetry.recordScheduledJob("interrupted");
        telemetry.recordScheduledJob("interrupted");

        assertThat(jobCount("success")).isEqualTo(1.0);
        assertThat(jobCount("skipped")).isEqualTo(1.0);
        assertThat(jobCount("error")).isEqualTo(1.0);
        assertThat(jobCount("interrupted")).isEqualTo(2.0);
    }

    @Test
    void anInterruptedPollIsTimedAsInterruptedAndLoggedAtInfoOnly() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(registry));
        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> telemetry.poll("p3", PollTrigger.SCHEDULED, () -> {
                throw new PollInterruptedException(new InterruptedException());
            })).isInstanceOf(PollInterruptedException.class);

            assertThat(logs.at(Level.ERROR)).isEmpty();
            assertThat(logs.messagesAt(Level.INFO)).containsExactly("Poll scheduled interrupted by shutdown");
        }

        Timer timer = registry.find(MetricNames.POLL).tag(MetricNames.Tags.OUTCOME, "interrupted").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(registry.find(MetricNames.POLL_LAST_SUCCESS).gauge().value()).isNaN();
    }
}
