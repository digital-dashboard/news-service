package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestFailedException;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class FeedPollerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final FeedIngestService ingestService = mock(FeedIngestService.class);
    private final SimpleAsyncTaskExecutor executor = mock(SimpleAsyncTaskExecutor.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private FeedPoller poller;

    @BeforeEach
    void setUp() {
        PollingTelemetry telemetry = new PollingTelemetry(ObservationRegistry.create(), new SimpleMeterRegistry(), clock);
        poller = new FeedPoller(feedRepository, ingestService, telemetry, executor, clock);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private Feed feed(long id) {
        Feed f = mock(Feed.class);
        when(f.getId()).thenReturn(id);
        when(f.isEnabled()).thenReturn(true);
        return f;
    }

    private void enabledFeeds(Feed... feeds) {
        when(feedRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(feeds));
    }

    private void executorRunsTasksInline() {
        when(executor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return CompletableFuture.completedFuture(callable.call());
        });
    }

    @SuppressWarnings("unchecked")
    private Future<IngestReport> executorReturnsMockedFuture() {
        Future<IngestReport> future = mock(Future.class);
        when(executor.submit(any(Callable.class))).thenReturn((Future) future);
        return future;
    }

    @Test
    void pollingAggregatesReportsFromExecutor() {
        enabledFeeds(feed(1L), feed(2L));
        executorRunsTasksInline();
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
    void anExceptionThrownByOneFeedIsIsolatedAsFailedReportAndLoggedAtError() {
        enabledFeeds(feed(1L), feed(2L));
        executorRunsTasksInline();
        when(feedRepository.existsById(1L)).thenReturn(true);
        when(ingestService.refresh(1L)).thenThrow(new IllegalStateException("unexpected network failure"));
        when(ingestService.refresh(2L)).thenReturn(IngestReport.notModified(2L));

        try (LogCapture logs = LogCapture.start()) {
            AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.notModified()).isEqualTo(1);
            assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.UNEXPECTED_ERROR);
            assertThat(logs.messagesAt(Level.ERROR)).containsExactly("Failed to ingest feed 1");
            assertThat(LogCapture.keyValues(logs.at(Level.ERROR).getFirst()))
                    .containsEntry("feedId", 1L)
                    .containsEntry("reason", FailureReasons.UNEXPECTED_ERROR);
        }
    }

    @Test
    void anIngestFailureThatTheIngestAlreadyLoggedIsReportedButNotLoggedAgain() {
        enabledFeeds(feed(1L));
        executorRunsTasksInline();
        when(feedRepository.existsById(1L)).thenReturn(true);
        when(ingestService.refresh(1L)).thenThrow(
                new IngestFailedException(1L, FailureReasons.PERSIST_FAILED, new IllegalStateException("db down")));

        try (LogCapture logs = LogCapture.start()) {
            AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

            assertThat(report.failed()).isOne();
            assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.UNEXPECTED_ERROR);
            assertThat(logs.at(Level.ERROR, FeedPoller.class)).isEmpty();
            assertThat(logs.at(Level.WARN, FeedPoller.class)).isEmpty();
        }
    }

    @Test
    void aFeedDeletedBeforeItsRefreshIsAWarnWithoutAStackTrace() {
        enabledFeeds(feed(1L));
        executorRunsTasksInline();
        when(ingestService.refresh(1L)).thenThrow(new ApiException(ErrorCode.FEED_NOT_FOUND, "gone"));

        try (LogCapture logs = LogCapture.start()) {
            AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

            assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.FEED_DELETED);
            assertThat(logs.at(Level.ERROR)).isEmpty();
            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Feed 1 was deleted during the poll");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @Test
    void aFailureOnAFeedThatNoLongerExistsIsReportedAsDeletedNotAsAnError() {
        enabledFeeds(feed(1L));
        executorRunsTasksInline();
        when(ingestService.refresh(1L)).thenThrow(new IllegalStateException("foreign key violation"));
        when(feedRepository.existsById(1L)).thenReturn(false);

        try (LogCapture logs = LogCapture.start()) {
            AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

            assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.FEED_DELETED);
            assertThat(logs.at(Level.ERROR)).isEmpty();
        }
    }

    @Test
    void anApiExceptionOtherThanNotFoundIsAnUnexpectedFailure() {
        enabledFeeds(feed(1L));
        executorRunsTasksInline();
        when(ingestService.refresh(1L)).thenThrow(new ApiException(ErrorCode.INTERNAL_ERROR, "boom"));
        when(feedRepository.existsById(1L)).thenReturn(true);

        AggregatePollReport report = poller.poll(PollTrigger.SCHEDULED);

        assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.UNEXPECTED_ERROR);
    }

    @Test
    void anExceptionOnAnInterruptedWorkerIsAnInterruptedReportWithoutAnErrorLog() {
        enabledFeeds(feed(1L));
        executorRunsTasksInline();
        when(ingestService.refresh(1L)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("connection acquisition interrupted");
        });

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> poller.poll(PollTrigger.SCHEDULED)).isInstanceOf(PollInterruptedException.class);

            assertThat(logs.at(Level.ERROR)).isEmpty();
        }
    }

    @Test
    void anExecutionExceptionFromAWorkerBecomesAFailedReport() throws Exception {
        enabledFeeds(feed(1L));
        Future<IngestReport> future = executorReturnsMockedFuture();
        when(future.get()).thenThrow(new ExecutionException(new AssertionError("worker died")));

        try (LogCapture logs = LogCapture.start()) {
            AggregatePollReport report = poller.poll(PollTrigger.MANUAL);

            assertThat(report.reports().getFirst().failureReason()).isEqualTo(FailureReasons.UNEXPECTED_ERROR);
            assertThat(logs.messagesAt(Level.ERROR)).containsExactly("Unexpected execution exception for feed 1");
        }
    }

    @Test
    void anInterruptWhileWaitingCancelsTheFuturesKeepsTheFlagAndEndsThePoll() throws Exception {
        enabledFeeds(feed(1L), feed(2L));
        Future<IngestReport> future = executorReturnsMockedFuture();
        when(future.get()).thenThrow(new InterruptedException("test interrupt"));

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(future, times(2)).cancel(true);
    }

    @Test
    void aCancelledFutureEndsThePollWithoutSettingTheInterruptFlag() throws Exception {
        enabledFeeds(feed(1L));
        Future<IngestReport> future = executorReturnsMockedFuture();
        when(future.get()).thenThrow(new CancellationException("executor closed"));

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        verify(future).cancel(true);
    }

    @Test
    void aWorkerThatReportsInterruptedEndsThePoll() {
        enabledFeeds(feed(1L), feed(2L));
        executorRunsTasksInline();
        when(ingestService.refresh(1L)).thenReturn(IngestReport.failed(1L, FailureReasons.INTERRUPTED));

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);
    }

    @Test
    void aRejectedSubmitStopsSubmittingAndCancelsWhatWasSubmitted() {
        enabledFeeds(feed(1L), feed(2L), feed(3L));
        Future<IngestReport> first = mock(Future.class);
        when(executor.submit(any(Callable.class)))
                .thenReturn((Future) first)
                .thenThrow(new TaskRejectedException("executor closed"));

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);

        verify(first).cancel(true);
        verify(executor, times(2)).submit(any(Callable.class));
    }

    @Test
    void aPlainRejectedExecutionAlsoEndsThePoll() {
        enabledFeeds(feed(1L));
        when(executor.submit(any(Callable.class))).thenThrow(new RejectedExecutionException("full"));

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);
    }

    @Test
    void anInterruptedOrchestratorSubmitsNothing() {
        enabledFeeds(feed(1L), feed(2L));
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> poller.poll(PollTrigger.MANUAL)).isInstanceOf(PollInterruptedException.class);

        verify(executor, never()).submit(any(Callable.class));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
