package com.j11a.argus.feed.poll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FeedPollingScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(FeedPollingScheduler.class);

    private final FeedPoller poller;
    private final PollingTelemetry telemetry;

    public FeedPollingScheduler(FeedPoller poller, PollingTelemetry telemetry) {
        this.poller = poller;
        this.telemetry = telemetry;
    }

    @Scheduled(cron = "${argus.poll.cron}")
    public void runScheduledPoll() {
        try {
            poller.poll(PollTrigger.SCHEDULED);
            telemetry.recordScheduledJobSuccess();
        } catch (InvocationRejectedException e) {
            LOG.warn("Scheduled poll skipped: another poll is already in progress");
            telemetry.recordScheduledJobSkipped();
        } catch (Exception e) {
            LOG.error("Scheduled poll error", e);
            telemetry.recordScheduledJobError();
        }
    }
}
