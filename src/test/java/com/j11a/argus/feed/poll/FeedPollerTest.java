package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

class FeedPollerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final FeedIngestService ingestService = mock(FeedIngestService.class);
    private final SimpleAsyncTaskExecutor executor = mock(SimpleAsyncTaskExecutor.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PollingTelemetry telemetry;
    private FeedPoller poller;

    @BeforeEach
    void setUp() {
        telemetry = new PollingTelemetry(ObservationRegistry.create(), new SimpleMeterRegistry(), clock);
        poller = new FeedPoller(feedRepository, ingestService, telemetry, executor, clock);
    }

    private Feed feed(long id) {
        Feed f = mock(Feed.class);
        when(f.getId()).thenReturn(id);
        when(f.isEnabled()).thenReturn(true);
        return f;
    }

    @Test
    void pollingAggregatesReportsFromExecutor() {
        Feed f1 = feed(1L);
        Feed f2 = feed(2L);
        when(feedRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(f1, f2));

        when(executor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return CompletableFuture.completedFuture(callable.call());
        });

        when(ingestService.refresh(1L)).thenReturn(IngestReport.failed(1L, "http_status"));
        when(ingestService.refresh(2L)).thenReturn(IngestReport.notModified(2L));

        AggregatePollReport report = poller.poll(PollTrigger.MANUAL);

        assertThat(report.feedsPolled()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.notModified()).isEqualTo(1);
        assertThat(report.succeeded()).isZero();
        assertThat(report.reports()).hasSize(2);
    }

    @Test
    void anExceptionThrownByOneFeedIsIsolatedAsFailedReport() {
        Feed f1 = feed(1L);
        Feed f2 = feed(2L);
        when(feedRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(f1, f2));

        when(executor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return CompletableFuture.completedFuture(callable.call());
        });

        when(ingestService.refresh(1L)).thenThrow(new RuntimeException("unexpected network failure"));
        when(ingestService.refresh(2L)).thenReturn(IngestReport.notModified(2L));

        AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

        assertThat(report.feedsPolled()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.notModified()).isEqualTo(1);
        assertThat(report.reports().getFirst().outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.reports().getFirst().failureReason()).isEqualTo("unexpected_error");
    }

    @Test
    void whenInterruptedThrowsIllegalStateException() {
        Feed f1 = feed(1L);
        when(feedRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(f1));

        @SuppressWarnings("unchecked")
        Future<IngestReport> future = mock(Future.class);
        when(executor.submit(any(Callable.class))).thenReturn((Future) future);
        try {
            when(future.get()).thenThrow(new InterruptedException("test interrupt"));
        } catch (Exception ignored) {
        }

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Poll interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }
}
