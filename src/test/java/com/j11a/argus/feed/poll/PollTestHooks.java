package com.j11a.argus.feed.poll;

import org.springframework.context.ApplicationContext;

/** Lets integration tests in other packages reach the package-private scheduler and telemetry beans. */
public final class PollTestHooks {

    private PollTestHooks() {
    }

    public static void runScheduledPoll(ApplicationContext context) {
        context.getBean(FeedPollingScheduler.class).runScheduledPoll();
    }

    public static void recordScheduledJob(ApplicationContext context, String outcome) {
        context.getBean(PollingTelemetry.class).recordScheduledJob(outcome);
    }
}
