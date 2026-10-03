package com.j11a.argus.feed.poll;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.ConcurrencyLimit.ThrottlePolicy;
import org.springframework.stereotype.Component;

/**
 * Polls every enabled feed on the poll executor. On shutdown the outstanding feeds are cancelled and the poll ends
 * with PollInterruptedException; feeds that already finished stay persisted.
 */
@Slf4j
@Component
public class FeedPoller {

    private final FeedRepository feedRepository;
    private final FeedIngestService ingestService;
    private final PollingTelemetry telemetry;
    private final SimpleAsyncTaskExecutor pollExecutor;
    private final Clock clock;

    public FeedPoller(FeedRepository feedRepository, FeedIngestService ingestService,
            PollingTelemetry telemetry, SimpleAsyncTaskExecutor pollExecutor, Clock clock) {
        this.feedRepository = feedRepository;
        this.ingestService = ingestService;
        this.telemetry = telemetry;
        this.pollExecutor = pollExecutor;
        this.clock = clock;
    }

    @ConcurrencyLimit(value = 1, policy = ThrottlePolicy.REJECT)
    public AggregatePollReport poll(PollTrigger trigger) {
        String pollId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        return telemetry.poll(pollId, trigger, () -> {
            List<Feed> enabledFeeds = feedRepository.findByEnabledTrueOrderByIdAsc();
            List<Future<IngestReport>> futures = submitAll(enabledFeeds);
            List<IngestReport> reports = collect(enabledFeeds, futures);
            Duration duration = Duration.between(startedAt, clock.instant());
            return AggregatePollReport.of(pollId, trigger, duration, reports);
        });
    }

    private List<Future<IngestReport>> submitAll(List<Feed> feeds) {
        List<Future<IngestReport>> futures = new ArrayList<>(feeds.size());
        try {
            for (Feed feed : feeds) {
                if (Thread.currentThread().isInterrupted()) {
                    // The flag stays set; Spring's throttle also leaves it set when it rejects an interrupted submit.
                    throw interrupted(futures, new InterruptedException("Interrupted while submitting feeds"));
                }
                futures.add(pollExecutor.submit(() -> refreshFeed(feed)));
            }
        } catch (RejectedExecutionException e) {
            // Closed executor, or the submit was interrupted while waiting for a free slot (Spring rejects then).
            throw interrupted(futures, e);
        }
        return futures;
    }

    private List<IngestReport> collect(List<Feed> feeds, List<Future<IngestReport>> futures) {
        List<IngestReport> reports = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                reports.add(awaitReport(futures.get(i)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw interrupted(futures, e);
            } catch (CancellationException e) {
                // The executor cancels its remaining tasks when it closes; nobody interrupted this thread.
                throw interrupted(futures, e);
            } catch (ExecutionException e) {
                log.atError()
                        .setMessage("Unexpected execution exception for feed " + feeds.get(i).getId())
                        .addKeyValue(LogKeys.FEED_ID, feeds.get(i).getId())
                        .setCause(e)
                        .log();
                reports.add(IngestReport.failed(feeds.get(i).getId(), FailureReasons.UNEXPECTED_ERROR));
            }
        }
        return reports;
    }

    // A worker interrupted by the executor closing reports "interrupted"; that ends the poll like any other interrupt.
    private static IngestReport awaitReport(Future<IngestReport> future)
            throws InterruptedException, ExecutionException {
        IngestReport report = future.get();
        if (FailureReasons.INTERRUPTED.equals(report.failureReason())) {
            throw new CancellationException("Feed " + report.feedId() + " was interrupted");
        }
        return report;
    }

    private static PollInterruptedException interrupted(List<Future<IngestReport>> futures, Exception cause) {
        futures.forEach(future -> future.cancel(true));
        return new PollInterruptedException(cause);
    }

    private IngestReport refreshFeed(Feed feed) {
        try {
            return ingestService.refresh(feed.getId());
        } catch (RuntimeException e) {
            boolean notFound = e instanceof ApiException api && api.code() == ErrorCode.FEED_NOT_FOUND;
            return notFound ? deleted(feed) : unexpected(feed, e);
        }
    }

    private IngestReport unexpected(Feed feed, RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {
            return IngestReport.failed(feed.getId(), FailureReasons.INTERRUPTED);
        }
        if (!feedRepository.existsById(feed.getId())) {
            return deleted(feed);
        }
        log.atError()
                .setMessage("Failed to ingest feed " + feed.getId())
                .addKeyValue(LogKeys.FEED_ID, feed.getId())
                .addKeyValue(LogKeys.REASON, FailureReasons.UNEXPECTED_ERROR)
                .setCause(e)
                .log();
        return IngestReport.failed(feed.getId(), FailureReasons.UNEXPECTED_ERROR);
    }

    private static IngestReport deleted(Feed feed) {
        log.atWarn()
                .setMessage("Feed " + feed.getId() + " was deleted during the poll")
                .addKeyValue(LogKeys.FEED_ID, feed.getId())
                .log();
        return IngestReport.failed(feed.getId(), FailureReasons.FEED_DELETED);
    }
}
