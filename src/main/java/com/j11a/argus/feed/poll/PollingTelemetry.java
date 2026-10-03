package com.j11a.argus.feed.poll;

import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class PollingTelemetry {

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
            List<Long> failedFeedIds = report.failedFeedIds();
            log.atInfo()
                    .setMessage("Poll " + report.trigger().tag() + " completed: duration=" + report.durationMs()
                            + "ms feeds=" + report.feedsPolled() + " succeeded=" + report.succeeded()
                            + " not_modified=" + report.notModified() + " failed=" + report.failed()
                            + " inserted=" + report.inserted() + " failedFeedIds=" + failedFeedIds)
                    .addKeyValue(LogKeys.DURATION_MS, report.durationMs())
                    .addKeyValue(LogKeys.FEEDS_POLLED, report.feedsPolled())
                    .addKeyValue(LogKeys.FAILED, report.failed())
                    .addKeyValue(LogKeys.FAILED_FEED_IDS, failedFeedIds)
                    .log();
            return report;
        } catch (PollInterruptedException e) {
            outcome = OUTCOME_INTERRUPTED;
            log.info("Poll {} interrupted by shutdown", trigger.tag());
            throw e;
        } catch (RuntimeException e) {
            observation.error(e);
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
