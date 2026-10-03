package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Not transactional: fetch and parse must not hold a connection. The persist step has its own transaction.
 * A fetch or parse failure is a FAILED report; anything unexpected propagates to the caller.
 */
@Service
public class FeedIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(FeedIngestService.class);
    private static final String PERSIST_SPAN = "argus.persist";

    private final FeedRepository feeds;
    private final FeedLoader loader;
    private final ArticlePersister persister;
    private final IngestTelemetry telemetry;
    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final Clock clock;

    public FeedIngestService(FeedRepository feeds, FeedLoader loader, ArticlePersister persister,
            IngestTelemetry telemetry, FeedHealthUpdater healthUpdater,
            FeedHealthGauges healthGauges, Clock clock) {
        this.feeds = feeds;
        this.loader = loader;
        this.persister = persister;
        this.telemetry = telemetry;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.clock = clock;
    }

    public IngestReport refresh(long feedId) {
        Feed feed = feeds.findWithSourceById(feedId)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + feedId + " does not exist."));
        return ingest(feed, () -> refreshLoaded(feed));
    }

    private IngestReport refreshLoaded(Feed feed) {
        FetchValidators validators = new FetchValidators(feed.getEtag(), feed.getLastModified());
        Instant now = clock.instant();
        FeedLoader.Loaded loaded;
        try {
            loaded = loader.load(URI.create(feed.getUrl()), validators, feed.getSource().getKey());
        } catch (RuntimeException e) {
            recordFailureUnlessInterrupted(feed, FailureReasons.UNEXPECTED_ERROR, now);
            throw e;
        }
        if (Thread.currentThread().isInterrupted()) {
            return IngestReport.failed(feed.getId(), FailureReasons.INTERRUPTED);
        }
        return switch (loaded) {
            case FeedLoader.Loaded.Parsed(var parsedFeed, var fetchedAt, var newValidators) ->
                    persistAndRecord(feed, parsedFeed, fetchedAt, newValidators, now);
            case FeedLoader.Loaded.NotModified(var notModified) -> {
                healthUpdater.recordNotModified(feed.getId(), notModified.validators(), now);
                yield IngestReport.notModified(feed.getId());
            }
            case FeedLoader.Loaded.Failed failed -> {
                healthUpdater.recordFailure(feed.getId(), failed.lastError(), now);
                yield IngestReport.failed(feed.getId(), failed.reason());
            }
        };
    }

    private IngestReport persistAndRecord(Feed feed, ParsedFeed parsed, Instant fetchedAt,
            FetchValidators validators, Instant now) {
        try {
            IngestReport report = persist(feed, parsed, fetchedAt);
            healthUpdater.recordSuccess(feed.getId(), validators, now);
            return report;
        } catch (RuntimeException e) {
            recordFailureUnlessInterrupted(feed, FailureReasons.PERSIST_FAILED, now);
            throw e;
        }
    }

    // A shutdown interrupt is not the feed's fault, so it must not count against it.
    private void recordFailureUnlessInterrupted(Feed feed, String reason, Instant now) {
        if (!Thread.currentThread().isInterrupted()) {
            healthUpdater.recordFailure(feed.getId(), reason, now);
        }
    }

    /** For callers that already fetched and parsed the feed: no second download. */
    public IngestReport ingestParsed(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        return ingest(feed, () -> persist(feed, parsed, fetchedAt));
    }

    private IngestReport ingest(Feed feed, Supplier<IngestReport> work) {
        try (MDC.MDCCloseable feedScope = MDC.putCloseable("feedId", String.valueOf(feed.getId()));
                MDC.MDCCloseable sourceScope = MDC.putCloseable("sourceId", String.valueOf(feed.getSource().getId()))) {
            try {
                IngestReport report = telemetry.ingest(feed, work);
                LOG.info("Ingest {}: seen={} inserted={} updated={} linked={} unchanged={} skipped={} reason={}",
                        report.outcome(), report.entriesSeen(), report.inserted(), report.updated(), report.linked(),
                        report.unchanged(), report.skipped(), report.failureReason());
                return report;
            } finally {
                if (!Thread.currentThread().isInterrupted()) {
                    healthGauges.refreshAfterCommit();
                }
            }
        }
    }

    private IngestReport persist(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        String sourceKey = feed.getSource().getKey();
        PersistCounts counts = telemetry.span(PERSIST_SPAN,
                () -> persister.persist(feed, parsed.entries(), fetchedAt));
        telemetry.recordMissing(sourceKey, parsed.entries());
        telemetry.recordDecisions(sourceKey, counts);
        telemetry.recordLinkFallbacks(sourceKey, counts.linkFallbacks());
        return IngestReport.completed(feed.getId(), parsed.entries().size(), counts);
    }
}
