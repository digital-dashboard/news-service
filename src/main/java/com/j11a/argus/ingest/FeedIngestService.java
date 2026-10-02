package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
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

    public FeedIngestService(FeedRepository feeds, FeedLoader loader, ArticlePersister persister,
            IngestTelemetry telemetry) {
        this.feeds = feeds;
        this.loader = loader;
        this.persister = persister;
        this.telemetry = telemetry;
    }

    public IngestReport refresh(long feedId) {
        Feed feed = feeds.findWithSourceById(feedId)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + feedId + " does not exist."));
        return ingest(feed, () -> {
            FeedLoader.Loaded loaded = loader.load(URI.create(feed.getUrl()), feed.getSource().getKey());
            return switch (loaded) {
                case FeedLoader.Loaded.Parsed(var parsedFeed, var fetchedAt) -> persist(feed, parsedFeed, fetchedAt);
                case FeedLoader.Loaded.Failed(var reason) -> IngestReport.failed(feed.getId(), reason);
            };
        });
    }

    /** For callers that already fetched and parsed the feed: no second download. */
    public IngestReport ingestParsed(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        return ingest(feed, () -> persist(feed, parsed, fetchedAt));
    }

    private IngestReport ingest(Feed feed, Supplier<IngestReport> work) {
        try (MDC.MDCCloseable feedScope = MDC.putCloseable("feedId", String.valueOf(feed.getId()));
                MDC.MDCCloseable sourceScope = MDC.putCloseable("sourceId", String.valueOf(feed.getSource().getId()))) {
            IngestReport report = telemetry.ingest(feed, work);
            LOG.info("Ingest {}: seen={} inserted={} unchanged={} skipped={} reason={}", report.outcome(),
                    report.entriesSeen(), report.inserted(), report.unchanged(), report.skipped(),
                    report.failureReason());
            return report;
        }
    }

    private IngestReport persist(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        String sourceKey = feed.getSource().getKey();
        PersistCounts counts = telemetry.span(PERSIST_SPAN,
                () -> persister.persist(feed, parsed.entries(), fetchedAt));
        telemetry.recordMissing(sourceKey, parsed.entries());
        telemetry.recordDecisions(sourceKey, counts);
        return IngestReport.completed(feed.getId(), parsed.entries().size(), counts);
    }
}
