package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.observability.LogKeys;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Ingests a new feed from the download that created it, without turning a failure into a failed create. */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FirstIngest {

    private final FeedIngestService ingest;
    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final Clock clock;

    FirstIngest(FeedIngestService ingest, FeedHealthUpdater healthUpdater, FeedHealthGauges healthGauges,
            Clock clock) {
        this.ingest = ingest;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.clock = clock;
    }

    void run(Feed feed, FeedLoader.CreateLoaded.Created loaded) {
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, loaded.feed(), loaded.fetchedAt());
            healthUpdater.recordSuccess(feed.getId(), loaded.validators(), clock.instant());
        } catch (RuntimeException e) {
            log.atError()
                    .setMessage("First ingest of feed " + feed.getId()
                            + " failed; the feed was created and a refresh will retry")
                    .addKeyValue(LogKeys.FEED_ID, feed.getId())
                    .setCause(e)
                    .log();
            healthUpdater.recordFailure(feed.getId(), FailureReasons.FIRST_INGEST_FAILED, clock.instant());
        } finally {
            healthGauges.refreshAfterCommit();
        }
    }
}
