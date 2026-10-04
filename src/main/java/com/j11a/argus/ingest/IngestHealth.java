package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FailingThreshold;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** Records what an ingest means for the feed's health, and logs it under the ingest service's logger. */
@Slf4j(topic = FeedIngestService.LOGGER_NAME)
@Component
class IngestHealth {

    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final int failingThreshold;

    IngestHealth(FeedHealthUpdater healthUpdater, FeedHealthGauges healthGauges, FailingThreshold failingThreshold) {
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.failingThreshold = failingThreshold.value();
    }

    static String feedLabel(long feedId, String sourceKey) {
        return "Feed " + feedId + " (" + sourceKey + ")";
    }

    void recordSuccess(Feed feed, FetchValidators validators, Instant now) {
        logRecovery(feed, healthUpdater.recordSuccess(feed.getId(), validators, now));
    }

    void recordNotModified(Feed feed, FetchValidators validators, Instant now) {
        logRecovery(feed, healthUpdater.recordNotModified(feed.getId(), validators, now));
    }

    void recordFetchFailure(Feed feed, FeedLoader.Loaded.Failed failed, Instant now) {
        int consecutiveFailures = healthUpdater.recordFailure(feed.getId(), failed.lastError(), now);
        logFailure(feed, failed, consecutiveFailures, null);
    }

    void refreshGauges() {
        healthGauges.refreshAfterCommit();
    }

    /**
     * Counts the failure against the feed and logs it once, with the stack trace, as an IngestFailedException's
     * cause; callers must not log it again. A shutdown interrupt, or a feed deleted meanwhile, is not the feed's
     * fault, so nothing is logged and the original exception goes on.
     */
    RuntimeException failedUnexpectedly(Feed feed, String reason, RuntimeException cause, Instant now) {
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
}
