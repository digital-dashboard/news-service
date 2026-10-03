package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
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

    @Test
    void scheduledJobCountersIncrementCorrectly() {
        telemetry.recordScheduledJobSuccess();
        telemetry.recordScheduledJobSkipped();
        telemetry.recordScheduledJobError();

        assertThat(registry.find(MetricNames.SCHEDULED_JOB)
                .tag(MetricNames.Tags.OUTCOME, "success")
                .counter().count()).isEqualTo(1.0);

        assertThat(registry.find(MetricNames.SCHEDULED_JOB)
                .tag(MetricNames.Tags.OUTCOME, "skipped")
                .counter().count()).isEqualTo(1.0);

        assertThat(registry.find(MetricNames.SCHEDULED_JOB)
                .tag(MetricNames.Tags.OUTCOME, "error")
                .counter().count()).isEqualTo(1.0);
    }
}
