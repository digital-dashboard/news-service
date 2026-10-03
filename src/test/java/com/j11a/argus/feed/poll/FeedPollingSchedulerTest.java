package com.j11a.argus.feed.poll;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.resilience.InvocationRejectedException;

class FeedPollingSchedulerTest {

    private final FeedPoller poller = mock(FeedPoller.class);
    private final PollingTelemetry telemetry = mock(PollingTelemetry.class);
    private final FeedPollingScheduler scheduler = new FeedPollingScheduler(poller, telemetry);

    @Test
    void successfulPollRecordsScheduledJobSuccess() {
        scheduler.runScheduledPoll();

        verify(poller).poll(PollTrigger.SCHEDULED);
        verify(telemetry).recordScheduledJobSuccess();
    }

    @Test
    void rejectedPollRecordsScheduledJobSkipped() {
        when(poller.poll(PollTrigger.SCHEDULED)).thenThrow(new InvocationRejectedException("running", poller));

        scheduler.runScheduledPoll();

        verify(telemetry).recordScheduledJobSkipped();
    }

    @Test
    void thrownExceptionRecordsScheduledJobError() {
        when(poller.poll(PollTrigger.SCHEDULED)).thenThrow(new RuntimeException("database down"));

        scheduler.runScheduledPoll();

        verify(telemetry).recordScheduledJobError();
    }
}
