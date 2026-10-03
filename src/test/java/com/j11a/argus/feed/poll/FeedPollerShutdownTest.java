package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.config.ArgusConfiguration;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.ThreadDumps;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Runs the poller on the executor ArgusConfiguration builds. Two feeds finish, two are held until they are interrupted
 * and a fifth waits for a free slot, so the orchestrator is blocked inside the executor's throttle.
 */
class FeedPollerShutdownTest {

    private static final Duration BOUND = Duration.ofSeconds(12);
    private static final int CONCURRENCY = 2;
    private static final String POLL_THREAD_PREFIX = "argus-poll-";
    private static final long FIRST_HELD_FEED = 3;
    private static final long LAST_FEED = 5;

    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final FeedIngestService ingestService = mock(FeedIngestService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CountDownLatch heldFeedsRunning = new CountDownLatch(2);
    private final CountDownLatch neverReleased = new CountDownLatch(1);
    private final AtomicInteger runningWorkers = new AtomicInteger();
    private final AtomicReference<Thread> orchestrator = new AtomicReference<>();
    private SimpleAsyncTaskExecutor executor;
    private FeedPoller poller;

    @BeforeEach
    void setUp() {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        executor = new ArgusConfiguration().pollExecutor(new PollProperties("-", CONCURRENCY, 3));
        poller = new FeedPoller(feedRepository, ingestService,
                new PollingTelemetry(observations, meters, Clock.systemUTC()), executor, Clock.systemUTC());
        List<Feed> feeds = List.of(feed(1), feed(2), feed(FIRST_HELD_FEED), feed(4), feed(LAST_FEED));
        when(feedRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(feeds);
        when(ingestService.refresh(anyLong())).thenAnswer(invocation -> work(invocation.getArgument(0)));
    }

    @AfterEach
    void closeExecutor() {
        executor.close();
        Thread.interrupted();
    }

    private static Feed feed(long id) {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(id);
        return feed;
    }

    private IngestReport work(long feedId) {
        runningWorkers.incrementAndGet();
        try {
            if (feedId < FIRST_HELD_FEED) {
                return IngestReport.notModified(feedId);
            }
            heldFeedsRunning.countDown();
            neverReleased.await();
            return IngestReport.notModified(feedId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IngestReport.failed(feedId, FailureReasons.INTERRUPTED);
        } finally {
            runningWorkers.decrementAndGet();
        }
    }

    private CompletableFuture<AggregatePollReport> startPoll() throws InterruptedException {
        CompletableFuture<AggregatePollReport> poll = CompletableFuture.supplyAsync(() -> {
            orchestrator.set(Thread.currentThread());
            return poller.poll(PollTrigger.SCHEDULED);
        });
        assertThat(heldFeedsRunning.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return poll;
    }

    private void assertEndedCleanly(CompletableFuture<AggregatePollReport> poll, LogCapture logs) {
        assertThatThrownBy(() -> poll.get(BOUND.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PollInterruptedException.class);
        // The poll returns as soon as it has cancelled the workers; they unwind on their own threads afterwards.
        await().atMost(BOUND).untilAsserted(() -> assertThat(runningWorkers).hasValue(0));
        await().atMost(BOUND).untilAsserted(() ->
                assertThat(ThreadDumps.namesStartingWith(POLL_THREAD_PREFIX)).isEmpty());
        verify(ingestService, never()).refresh(LAST_FEED);
        assertThat(logs.at(Level.ERROR)).isEmpty();
        Timer interrupted = meters.find(MetricNames.POLL).tag(MetricNames.Tags.OUTCOME, "interrupted").timer();
        assertThat(interrupted).isNotNull();
        assertThat(interrupted.count()).isEqualTo(1);
        assertThat(meters.find(MetricNames.POLL).tag(MetricNames.Tags.OUTCOME, "failed").timer()).isNull();
    }

    @Test
    void interruptingTheOrchestratorCancelsTheWorkersAndEndsThePollWithinTheBound() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            CompletableFuture<AggregatePollReport> poll = startPoll();

            orchestrator.get().interrupt();

            assertEndedCleanly(poll, logs);
            assertThat(logs.messagesAt(Level.INFO)).contains("Poll scheduled interrupted by shutdown");
        }
    }

    @Test
    void closingTheExecutorInterruptsTheWorkersAndEndsThePollWithinTheBound() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            CompletableFuture<AggregatePollReport> poll = startPoll();

            long started = System.nanoTime();
            executor.close();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
            assertEndedCleanly(poll, logs);
        }
    }
}
