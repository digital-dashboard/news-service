package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FailingThreshold;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
    private final int failingThreshold;
    private final FeedRedirectApplier redirectApplier;
    private final FeedIdentityRegistry identityRegistry;
    private final Clock clock;

    /** The feed as it is after a persist, and the report. The feed differs from the input after a source change. */
    private record Persisted(Feed feed, IngestReport report) {
    }

    public FeedIngestService(FeedRepository feeds, FeedLoader loader, ArticlePersister persister,
            IngestTelemetry telemetry, FeedHealthUpdater healthUpdater,
            FeedHealthGauges healthGauges, FailingThreshold failingThreshold, FeedRedirectApplier redirectApplier,
            FeedIdentityRegistry identityRegistry, Clock clock) {
        this.feeds = feeds;
        this.loader = loader;
        this.persister = persister;
        this.telemetry = telemetry;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.failingThreshold = failingThreshold.value();
        this.redirectApplier = redirectApplier;
        this.identityRegistry = identityRegistry;
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
            throw failedUnexpectedly(feed, FailureReasons.UNEXPECTED_ERROR, e, now);
        }
        if (Thread.currentThread().isInterrupted()) {
            return IngestReport.failed(feed.getId(), FailureReasons.INTERRUPTED);
        }
        return switch (loaded) {
            case FeedLoader.Loaded.Parsed(var parsedFeed, var fetchedAt, var newValidators, var target) ->
                    duplicateReport(feed, target, now)
                            .orElseGet(() -> persistAndRecord(feed, parsedFeed, fetchedAt, newValidators, now));
            case FeedLoader.Loaded.NotModified(var notModified) ->
                    duplicateReport(feed, notModified.permanentTarget(), now).orElseGet(() -> {
                        logRecovery(feed,
                                healthUpdater.recordNotModified(feed.getId(), notModified.validators(), now));
                        return IngestReport.notModified(feed.getId());
                    });
            case FeedLoader.Loaded.Failed failed -> {
                int consecutiveFailures = healthUpdater.recordFailure(feed.getId(), failed.lastError(), now);
                logFailure(feed, failed, consecutiveFailures, null);
                yield IngestReport.failed(feed.getId(), failed.reason());
            }
        };
    }

    /**
     * Applies a permanent redirect before anything is persisted. When it points at another feed the applier has
     * already disabled this one and logged it; the ingest ends there, and it is not a failure of the feed.
     */
    private Optional<IngestReport> duplicateReport(Feed feed, @Nullable URI permanentTarget, Instant now) {
        if (permanentTarget == null) {
            return Optional.empty();
        }
        FeedRedirectApplier.RedirectOutcome outcome;
        try {
            outcome = redirectApplier.apply(feed.getId(), feed.getSource().getKey(), feed.getUrl(), permanentTarget);
        } catch (RuntimeException e) {
            throw failedUnexpectedly(feed, FailureReasons.UNEXPECTED_ERROR, e, now);
        }
        return outcome instanceof FeedRedirectApplier.RedirectOutcome.Conflict
                ? Optional.of(IngestReport.failed(feed.getId(), FailureReasons.DUPLICATE_FEED))
                : Optional.empty();
    }

    private IngestReport persistAndRecord(Feed feed, ParsedFeed parsed, Instant fetchedAt,
            FetchValidators validators, Instant now) {
        try {
            Persisted persisted = persistWithRetry(feed, parsed, fetchedAt);
            if (persisted.report().outcome() == IngestReport.Outcome.FAILED) {
                return persisted.report();
            }
            Feed current = persisted.feed();
            logRecovery(current, healthUpdater.recordSuccess(current.getId(), validators, now));
            recordSelfUrl(current.getId(), parsed);
            return persisted.report();
        } catch (RuntimeException e) {
            throw failedUnexpectedly(feed, FailureReasons.PERSIST_FAILED, e, now);
        }
    }

    // Best effort: the self link is only a bonus identity, so no failure here may fail the ingest.
    private void recordSelfUrl(long feedId, ParsedFeed parsed) {
        try {
            identityRegistry.recordSelfUrl(feedId, parsed.selfLink());
        } catch (RuntimeException e) {
            log.atWarn()
                    .setMessage("Feed " + feedId + " could not record its self link")
                    .addKeyValue(LogKeys.FEED_ID, feedId)
                    .setCause(e)
                    .log();
        }
    }

    /**
     * The feed may have moved to another source since it was loaded, which the persister detects under the source
     * lock. One retry with the feed re-read follows the move; a second mismatch is not the feed's fault, so it is a
     * failed report that leaves the health counters alone.
     */
    private Persisted persistWithRetry(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        try {
            return new Persisted(feed, persist(feed, parsed, fetchedAt));
        } catch (FeedSourceChangedException first) {
            Feed current = feeds.findWithSourceById(feed.getId()).orElseThrow(() -> new ApiException(
                    ErrorCode.FEED_NOT_FOUND, "Feed " + feed.getId() + " does not exist."));
            MDC.put(LogKeys.SOURCE_ID, String.valueOf(current.getSource().getId()));
            try {
                return new Persisted(current, persist(current, parsed, fetchedAt));
            } catch (FeedSourceChangedException second) {
                log.atWarn()
                        .setMessage(feedLabel(current.getId(), current.getSource().getKey())
                                + " changed source twice during an ingest; nothing was written")
                        .addKeyValue(LogKeys.SOURCE_KEY, current.getSource().getKey())
                        .addKeyValue(LogKeys.REASON, FailureReasons.SOURCE_CHANGED)
                        .log();
                return new Persisted(current, IngestReport.failed(current.getId(), FailureReasons.SOURCE_CHANGED));
            }
        }
    }

    /**
     * Counts the failure against the feed and logs it once, with the stack trace, as an IngestFailedException's
     * cause; callers must not log it again. A shutdown interrupt, or a feed deleted meanwhile, is not the feed's
     * fault, so nothing is logged and the original exception goes on.
     */
    private RuntimeException failedUnexpectedly(Feed feed, String reason, RuntimeException cause, Instant now) {
        if (Thread.currentThread().isInterrupted()) {
            return cause;
        }
        int consecutiveFailures = healthUpdater.recordFailure(feed.getId(), reason, now);
        if (consecutiveFailures == 0) {
            // The feed was deleted meanwhile: not its failure, and the poller reports it as deleted.
            return cause;
        }
        // Only the exception type is logged: persistence errors quote the article row (GUID, link, title).
        FeedLoader.Loaded.Failed failed = new FeedLoader.Loaded.Failed(
                reason, null, FetchError.typeOnly(cause), null, null);
        logFailure(feed, failed, consecutiveFailures, cause);
        return new IngestFailedException(feed.getId(), reason, cause);
    }

    private void logFailure(Feed feed, FeedLoader.Loaded.Failed failed, int consecutiveFailures,
            @Nullable RuntimeException unexpected) {
        String sourceKey = feed.getSource().getKey();
        String detail = describe(failed.reason(), failed.error());
        failureFields(unexpected != null ? log.atError() : log.atWarn(), feed, failed, consecutiveFailures)
                .setMessage("Ingest failed for feed " + feed.getId() + " (" + sourceKey + "): " + detail + "; "
                        + consecutiveFailures + " consecutive failures")
                .setCause(unexpected)
                .log();
        // == fires once per crossing; a recovery followed by a fresh crossing fires again.
        if (consecutiveFailures == failingThreshold) {
            failureFields(log.atError(), feed, failed, consecutiveFailures)
                    .setMessage(feedLabel(feed.getId(), sourceKey) + " is now failing after "
                            + consecutiveFailures + " consecutive failures; last error: " + detail)
                    .log();
        }
    }

    // feedId and sourceId are already in the MDC for the whole ingest, so they are not repeated as key-values.
    private LoggingEventBuilder failureFields(LoggingEventBuilder event, Feed feed, FeedLoader.Loaded.Failed failed,
            int consecutiveFailures) {
        LoggingEventBuilder base = event
                .addKeyValue(LogKeys.SOURCE_KEY, feed.getSource().getKey())
                .addKeyValue(LogKeys.URL, HttpUrls.redact(feed.getUrl()))
                .addKeyValue(LogKeys.REASON, failed.reason())
                .addKeyValue(LogKeys.CONSECUTIVE_FAILURES, consecutiveFailures)
                .addKeyValue(LogKeys.FAILING_THRESHOLD, failingThreshold);
        LogFields.put(base, LogKeys.HTTP_STATUS, failed.httpStatus());
        return FetchError.addFields(base, failed.error(), failed.contentType(), failed.bodyBytes());
    }

    private static String describe(String reason, @Nullable FetchError error) {
        if (error == null) {
            return reason;
        }
        return reason + " " + error.type() + (error.message() != null ? ": " + error.message() : "");
    }

    private static String feedLabel(long feedId, String sourceKey) {
        return "Feed " + feedId + " (" + sourceKey + ")";
    }

    private void logRecovery(Feed feed, int previousFailures) {
        if (previousFailures <= 0) {
            return;
        }
        String sourceKey = feed.getSource().getKey();
        log.atInfo()
                .setMessage(feedLabel(feed.getId(), sourceKey) + " recovered after " + previousFailures
                        + " consecutive failures")
                .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                .addKeyValue(LogKeys.CONSECUTIVE_FAILURES, previousFailures)
                .log();
    }

    /** For callers that already fetched and parsed the feed: no second download. */
    public IngestReport ingestParsed(Feed feed, ParsedFeed parsed, Instant fetchedAt) {
        return ingest(feed, () -> persistWithRetry(feed, parsed, fetchedAt).report());
    }

    private IngestReport ingest(Feed feed, Supplier<IngestReport> work) {
        String sourceId = String.valueOf(feed.getSource().getId());
        try (MDC.MDCCloseable feedScope = MDC.putCloseable(LogKeys.FEED_ID, String.valueOf(feed.getId()));
                MDC.MDCCloseable sourceScope = MDC.putCloseable(LogKeys.SOURCE_ID, sourceId)) {
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
                        .addKeyValue(LogKeys.DURATION_MS,
                                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis())
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
