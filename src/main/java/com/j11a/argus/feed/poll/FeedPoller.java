package com.j11a.argus.feed.poll;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.ConcurrencyLimit.ThrottlePolicy;
import org.springframework.stereotype.Component;

@Component
public class FeedPoller {

    private static final Logger LOG = LoggerFactory.getLogger(FeedPoller.class);

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
            List<Future<IngestReport>> futures = new ArrayList<>(enabledFeeds.size());
            for (Feed feed : enabledFeeds) {
                futures.add(pollExecutor.submit(() -> refreshFeed(feed)));
            }
            List<IngestReport> reports = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                Feed feed = enabledFeeds.get(i);
                try {
                    reports.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Poll interrupted while waiting for feed " + feed.getId(), e);
                } catch (ExecutionException e) {
                    LOG.error("Unexpected execution exception for feed {}", feed.getId(), e);
                    reports.add(IngestReport.failed(feed.getId(), "unexpected_error"));
                }
            }
            Instant finishedAt = clock.instant();
            Duration duration = Duration.between(startedAt, finishedAt);
            return AggregatePollReport.of(pollId, trigger, duration, reports);
        });
    }

    private IngestReport refreshFeed(Feed feed) {
        try {
            return ingestService.refresh(feed.getId());
        } catch (Exception e) {
            LOG.error("Failed to ingest feed {}", feed.getId(), e);
            return IngestReport.failed(feed.getId(), "unexpected_error");
        }
    }
}
