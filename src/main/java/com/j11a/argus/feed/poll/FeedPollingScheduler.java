package com.j11a.argus.feed.poll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class FeedPollingScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(FeedPollingScheduler.class);

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
            LOG.warn("Scheduled poll skipped: another poll is already in progress");
            telemetry.recordScheduledJob(PollingTelemetry.JOB_SKIPPED);
        } catch (PollInterruptedException e) {
            LOG.info("Scheduled poll interrupted by shutdown");
            telemetry.recordScheduledJob(PollingTelemetry.OUTCOME_INTERRUPTED);
        } catch (RuntimeException e) {
            LOG.error("Scheduled poll error", e);
            telemetry.recordScheduledJob(PollingTelemetry.JOB_ERROR);
        }
    }
}
