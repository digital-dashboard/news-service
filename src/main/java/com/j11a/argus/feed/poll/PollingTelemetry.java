package com.j11a.argus.feed.poll;

import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
class PollingTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(PollingTelemetry.class);
    private static final String POLL_JOB = "poll";
    private static final String POLL_ID_MDC_KEY = "pollId";
    private static final String POLL_ID_SPAN_KEY = "poll.id";
    private static final String OUTCOME_COMPLETED = "completed";
    private static final String OUTCOME_FAILED = "failed";
    static final String OUTCOME_INTERRUPTED = "interrupted";
    static final String JOB_SUCCESS = "success";
    static final String JOB_SKIPPED = "skipped";
    static final String JOB_ERROR = "error";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    PollingTelemetry(ObservationRegistry observations, MeterRegistry meters, Clock clock) {
        this.observations = observations;
        this.meters = meters;
        this.clock = clock;
        Gauge.builder(MetricNames.POLL_LAST_SUCCESS, lastSuccess, ref -> {
            Instant instant = ref.get();
            return instant != null ? instant.getEpochSecond() : Double.NaN;
        }).baseUnit("seconds").description("Epoch seconds of the last successful poll").register(meters);
    }

    AggregatePollReport poll(String pollId, PollTrigger trigger, Supplier<AggregatePollReport> work) {
        Observation observation = Observation.createNotStarted(MetricNames.POLL, observations)
                .lowCardinalityKeyValue(MetricNames.Tags.TRIGGER, trigger.tag())
                .highCardinalityKeyValue(POLL_ID_SPAN_KEY, pollId)
                .start();
        String outcome = OUTCOME_FAILED;
        try (Observation.Scope ignored = observation.openScope();
                MDC.MDCCloseable pollScope = MDC.putCloseable(POLL_ID_MDC_KEY, pollId)) {
            AggregatePollReport report = work.get();
            outcome = OUTCOME_COMPLETED;
            lastSuccess.set(clock.instant());
            LOG.info("Poll {} completed: duration={}ms feeds={} succeeded={} not_modified={} failed={} inserted={}",
                    report.trigger().tag(), report.durationMs(), report.feedsPolled(),
                    report.succeeded(), report.notModified(), report.failed(), report.inserted());
            return report;
        } catch (PollInterruptedException e) {
            outcome = OUTCOME_INTERRUPTED;
            LOG.info("Poll {} interrupted by shutdown", trigger.tag());
            throw e;
        } catch (RuntimeException e) {
            observation.error(e);
            LOG.error("Poll {} failed", trigger.tag(), e);
            throw e;
        } finally {
            observation.lowCardinalityKeyValue(MetricNames.Tags.OUTCOME, outcome);
            observation.stop();
        }
    }

    void recordScheduledJob(String outcome) {
        meters.counter(MetricNames.SCHEDULED_JOB,
                MetricNames.Tags.SCHEDULED_JOB, POLL_JOB,
                MetricNames.Tags.OUTCOME, outcome).increment();
    }
}
