package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.testsupport.LogCapture;
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
        verify(telemetry).recordScheduledJob("success");
    }

    @Test
    void rejectedPollRecordsScheduledJobSkipped() {
        when(poller.poll(PollTrigger.SCHEDULED)).thenThrow(new InvocationRejectedException("running", poller));

        scheduler.runScheduledPoll();

        verify(telemetry).recordScheduledJob("skipped");
    }

    @Test
    void anInterruptedPollRecordsInterruptedAndLogsAtInfoNeverError() {
        when(poller.poll(PollTrigger.SCHEDULED)).thenThrow(new PollInterruptedException(new InterruptedException()));

        try (LogCapture logs = LogCapture.start()) {
            scheduler.runScheduledPoll();

            assertThat(logs.at(Level.ERROR)).isEmpty();
            assertThat(logs.messagesAt(Level.INFO)).containsExactly("Scheduled poll interrupted by shutdown");
        }

        verify(telemetry).recordScheduledJob("interrupted");
        verify(telemetry, never()).recordScheduledJob("error");
    }

    @Test
    void thrownExceptionRecordsScheduledJobErrorAndTheNextRunStillPolls() {
        when(poller.poll(PollTrigger.SCHEDULED)).thenThrow(new RuntimeException("database down"))
                .thenReturn(null);

        scheduler.runScheduledPoll();
        scheduler.runScheduledPoll();

        verify(telemetry).recordScheduledJob("error");
        verify(telemetry).recordScheduledJob("success");
        verify(poller, times(2)).poll(PollTrigger.SCHEDULED);
    }
}
