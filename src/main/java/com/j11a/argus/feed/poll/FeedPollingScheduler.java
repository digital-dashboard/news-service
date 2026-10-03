package com.j11a.argus.feed.poll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class FeedPollingScheduler {

    private final FeedPoller poller;
    private final PollingTelemetry telemetry;

    FeedPollingScheduler(FeedPoller poller, PollingTelemetry telemetry) {
        this.poller = poller;
        this.telemetry = telemetry;
    }

    @Scheduled(cron = "${argus.poll.cron}")
    void runScheduledPoll() {
        try {
            poller.poll(PollTrigger.SCHEDULED);
            telemetry.recordScheduledJob(PollingTelemetry.JOB_SUCCESS);
        } catch (InvocationRejectedException e) {
            log.warn("Scheduled poll skipped: another poll is already in progress");
            telemetry.recordScheduledJob(PollingTelemetry.JOB_SKIPPED);
        } catch (PollInterruptedException e) {
            log.info("Scheduled poll interrupted by shutdown");
            telemetry.recordScheduledJob(PollingTelemetry.OUTCOME_INTERRUPTED);
        } catch (RuntimeException e) {
            log.error("Scheduled poll error", e);
            telemetry.recordScheduledJob(PollingTelemetry.JOB_ERROR);
        }
    }
}
