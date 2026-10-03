package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;

/**
 * Not transactional: fetch and parse must not hold a connection. The persist step has its own transaction.
 * A fetch or parse failure is a FAILED report; anything unexpected propagates to the caller.
 */
@Slf4j
@Service
public class FeedIngestService {

    private static final String PERSIST_SPAN = "argus.persist";

    private final FeedRepository feeds;
    private final FeedLoader loader;
    private final ArticlePersister persister;
    private final IngestTelemetry telemetry;
    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final PollProperties pollProperties;
    private final Clock clock;

    /** What went wrong, as far as it is known; contentType and bodyBytes are only set for parse failures. */
    private record Failure(String reason, @Nullable Integer httpStatus, @Nullable FetchError error,
                           @Nullable String contentType, @Nullable Integer bodyBytes) {

        static Failure of(Throwable cause, String reason) {
            return new Failure(reason, null, FetchError.of(cause), null, null);
        }
    }

    public FeedIngestService(FeedRepository feeds, FeedLoader loader, ArticlePersister persister,
            IngestTelemetry telemetry, FeedHealthUpdater healthUpdater,
            FeedHealthGauges healthGauges, PollProperties pollProperties, Clock clock) {
        this.feeds = feeds;
        this.loader = loader;
        this.persister = persister;
        this.telemetry = telemetry;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.pollProperties = pollProperties;
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
            recordFailureUnlessInterrupted(feed, Failure.of(e, FailureReasons.UNEXPECTED_ERROR), now);
            throw e;
        }
        if (Thread.currentThread().isInterrupted()) {
            return IngestReport.failed(feed.getId(), FailureReasons.INTERRUPTED);
        }
        return switch (loaded) {
            case FeedLoader.Loaded.Parsed(var parsedFeed, var fetchedAt, var newValidators) ->
                    persistAndRecord(feed, parsedFeed, fetchedAt, newValidators, now);
            case FeedLoader.Loaded.NotModified(var notModified) -> {
                logRecovery(feed, healthUpdater.recordNotModified(feed.getId(), notModified.validators(), now));
                yield IngestReport.notModified(feed.getId());
            }
            case FeedLoader.Loaded.Failed failed -> {
                recordAndLogFailure(feed, new Failure(failed.reason(), failed.httpStatus(), failed.error(),
                        failed.contentType(), failed.bodyBytes()), failed.lastError(), now);
                yield IngestReport.failed(feed.getId(), failed.reason());
            }
        };
    }

    private IngestReport persistAndRecord(Feed feed, ParsedFeed parsed, Instant fetchedAt,
            FetchValidators validators, Instant now) {
        try {
            IngestReport report = persist(feed, parsed, fetchedAt);
            logRecovery(feed, healthUpdater.recordSuccess(feed.getId(), validators, now));
            return report;
        } catch (RuntimeException e) {
            recordFailureUnlessInterrupted(feed, Failure.of(e, FailureReasons.PERSIST_FAILED), now);
            throw e;
        }
    }

    // A shutdown interrupt is not the feed's fault, so it must not count against it.
    private void recordFailureUnlessInterrupted(Feed feed, Failure failure, Instant now) {
        if (!Thread.currentThread().isInterrupted()) {
            recordAndLogFailure(feed, failure, failure.reason(), now);
        }
    }

    private void recordAndLogFailure(Feed feed, Failure failure, String lastError, Instant now) {
        int consecutiveFailures = healthUpdater.recordFailure(feed.getId(), lastError, now);
        logFailure(feed, failure, consecutiveFailures);
    }

    private void logFailure(Feed feed, Failure failure, int consecutiveFailures) {
        String sourceKey = feed.getSource().getKey();
        int threshold = pollProperties.failingThreshold();
        String detail = describe(failure.reason(), failure.error());
        failureFields(log.atWarn(), feed, failure, consecutiveFailures)
                .setMessage("Ingest failed for feed " + feed.getId() + " (" + sourceKey + "): " + detail + "; "
                        + consecutiveFailures + " consecutive failures")
                .log();
        if (consecutiveFailures == threshold) {
            failureFields(log.atError(), feed, failure, consecutiveFailures)
                    .setMessage("Feed " + feed.getId() + " (" + sourceKey + ") is now failing after "
                            + consecutiveFailures + " consecutive failures; last error: " + detail)
                    .log();
        }
    }

    // feedId and sourceId are already in the MDC for the whole ingest, so they are not repeated as key-values.
    private LoggingEventBuilder failureFields(LoggingEventBuilder event, Feed feed, Failure failure,
            int consecutiveFailures) {
        FetchError error = failure.error();
        LoggingEventBuilder base = event
                .addKeyValue(LogKeys.SOURCE_KEY, feed.getSource().getKey())
                .addKeyValue(LogKeys.URL, HttpUrls.redact(feed.getUrl()))
                .addKeyValue(LogKeys.REASON, failure.reason())
                .addKeyValue(LogKeys.CONSECUTIVE_FAILURES, consecutiveFailures)
                .addKeyValue(LogKeys.FAILING_THRESHOLD, pollProperties.failingThreshold());
        LogFields.put(base, LogKeys.HTTP_STATUS, failure.httpStatus());
        LogFields.put(base, LogKeys.ERROR_TYPE, error != null ? error.type() : null);
        LogFields.put(base, LogKeys.ERROR_MESSAGE, error != null ? error.message() : null);
        LogFields.put(base, LogKeys.CONTENT_TYPE, failure.contentType());
        LogFields.put(base, LogKeys.BODY_BYTES, failure.bodyBytes());
        return base;
    }

    private static String describe(String reason, @Nullable FetchError error) {
        if (error == null) {
            return reason;
        }
        return reason + " " + error.type() + (error.message() != null ? ": " + error.message() : "");
    }

    private void logRecovery(Feed feed, int previousFailures) {
        if (previousFailures <= 0) {
            return;
        }
        String sourceKey = feed.getSource().getKey();
        log.atInfo()
                .setMessage("Feed " + feed.getId() + " (" + sourceKey + ") recovered after " + previousFailures
                        + " consecutive failures")
                .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                .addKeyValue(LogKeys.CONSECUTIVE_FAILURES, previousFailures)
                .log();
    }

    /** For callers that already fetched and parsed the feed: no second download. */
    public IngestReport ingestParsed(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        return ingest(feed, () -> persist(feed, parsed, fetchedAt));
    }

    private IngestReport ingest(Feed feed, Supplier<IngestReport> work) {
        try (MDC.MDCCloseable feedScope = MDC.putCloseable(LogKeys.FEED_ID, String.valueOf(feed.getId()));
                MDC.MDCCloseable sourceScope = MDC.putCloseable(LogKeys.SOURCE_ID, String.valueOf(feed.getSource().getId()))) {
            try {
                long startedNanos = System.nanoTime();
                IngestReport report = telemetry.ingest(feed, work);
                log.atInfo()
                        .setMessage("Ingest " + report.outcome() + ": seen=" + report.entriesSeen()
                                + " inserted=" + report.inserted() + " updated=" + report.updated()
                                + " linked=" + report.linked() + " unchanged=" + report.unchanged()
                                + " skipped=" + report.skipped() + " reason=" + report.failureReason())
                        .addKeyValue(LogKeys.SOURCE_KEY, feed.getSource().getKey())
                        .addKeyValue(LogKeys.URL, HttpUrls.redact(feed.getUrl()))
                        .addKeyValue(LogKeys.DURATION_MS, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis())
                        .log();
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
