package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.identity.FeedIdentityHooks;
import com.j11a.argus.feed.identity.FeedRedirectApplier.RedirectOutcome;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.web.error.ApiException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Not transactional: fetch and parse must not hold a connection. The persist step has its own transaction.
 * A fetch or parse failure is a FAILED report; anything unexpected propagates to the caller.
 */
@Slf4j(topic = FeedIngestService.LOGGER_NAME)
@Service
public class FeedIngestService {

    static final String LOGGER_NAME = "com.j11a.argus.ingest.FeedIngestService";
    private static final String PERSIST_SPAN = "argus.persist";

    private final FeedRepository feeds;
    private final FeedLoader loader;
    private final ArticlePersister persister;
    private final IngestTelemetry telemetry;
    private final IngestHealth health;
    private final FeedIdentityHooks identity;
    private final Clock clock;

    /** The feed as it is after a persist, and the report. The feed differs from the input after a source change. */
    private record Persisted(Feed feed, IngestReport report) {
    }

    FeedIngestService(FeedRepository feeds, FeedLoader loader, ArticlePersister persister,
            IngestTelemetry telemetry, IngestHealth health, FeedIdentityHooks identity, Clock clock) {
        this.feeds = feeds;
        this.loader = loader;
        this.persister = persister;
        this.telemetry = telemetry;
        this.health = health;
        this.identity = identity;
        this.clock = clock;
    }

    public IngestReport refresh(long feedId) {
        Feed feed = feeds.findWithSourceById(feedId)
                .orElseThrow(() -> ApiException.feedNotFound(feedId));
        return ingest(feed, () -> refreshLoaded(feed));
    }

    private IngestReport refreshLoaded(Feed feed) {
        FetchValidators validators = new FetchValidators(feed.getEtag(), feed.getLastModified());
        Instant now = clock.instant();
        FeedLoader.Loaded loaded;
        try {
            loaded = loader.load(URI.create(feed.getUrl()), validators, feed.getSource().getKey());
        } catch (RuntimeException e) {
            throw health.failedUnexpectedly(feed, FailureReasons.UNEXPECTED_ERROR, e, now);
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
                        health.recordNotModified(feed, notModified.validators(), now);
                        return IngestReport.notModified(feed.getId());
                    });
            case FeedLoader.Loaded.Failed failed -> {
                health.recordFetchFailure(feed, failed, now);
                yield IngestReport.failed(feed.getId(), failed.reason());
            }
        };
    }

    /**
     * Applies a permanent redirect before anything is persisted. When it points at another feed the applier has
     * already disabled this one and logged it; the ingest ends there, and it is not a failure of the feed. A redirect
     * that was skipped leaves the feed as it is, and the ingest goes on.
     */
    private Optional<IngestReport> duplicateReport(Feed feed, @Nullable URI permanentTarget, Instant now) {
        if (permanentTarget == null) {
            return Optional.empty();
        }
        RedirectOutcome outcome;
        try {
            outcome = identity.applyRedirect(feed.getId(), feed.getSource().getKey(), feed.getUrl(), permanentTarget);
        } catch (RuntimeException e) {
            throw health.failedUnexpectedly(feed, FailureReasons.UNEXPECTED_ERROR, e, now);
        }
        return outcome instanceof RedirectOutcome.Conflict
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
            health.recordSuccess(current, validators, now);
            identity.recordSelfUrl(current.getId(), parsed.selfLink());
            return persisted.report();
        } catch (RuntimeException e) {
            throw health.failedUnexpectedly(feed, FailureReasons.PERSIST_FAILED, e, now);
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
            Feed current = feeds.findWithSourceById(feed.getId())
                    .orElseThrow(() -> ApiException.feedNotFound(feed.getId()));
            MDC.put(LogKeys.SOURCE_ID, String.valueOf(current.getSource().getId()));
            try {
                return new Persisted(current, persist(current, parsed, fetchedAt));
            } catch (FeedSourceChangedException second) {
                log.atWarn()
                        .setMessage(IngestHealth.feedLabel(current.getId(), current.getSource().getKey())
                                + " changed source twice during an ingest; nothing was written")
                        .addKeyValue(LogKeys.SOURCE_KEY, current.getSource().getKey())
                        .addKeyValue(LogKeys.REASON, FailureReasons.SOURCE_CHANGED)
                        .log();
                return new Persisted(current, IngestReport.failed(current.getId(), FailureReasons.SOURCE_CHANGED));
            }
        }
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
                    health.refreshGauges();
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
