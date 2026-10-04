package com.j11a.argus.ingest;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.dedup.DedupInput;
import com.j11a.argus.ingest.dedup.EntryDedupResolver;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.source.SourceLock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A bean of its own so the transaction proxy applies when FeedIngestService calls it. */
@Component
public class ArticlePersister {

    private final SourceLock sourceLock;
    private final ExistingArticleLoader loader;
    private final EntryDedupResolver resolver;
    private final DecisionApplier applier;
    private final IngestTelemetry telemetry;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ArticlePersister(SourceLock sourceLock, ExistingArticleLoader loader, EntryDedupResolver resolver,
            DecisionApplier applier, IngestTelemetry telemetry, JdbcClient jdbc, Clock clock) {
        this.sourceLock = sourceLock;
        this.loader = loader;
        this.resolver = resolver;
        this.applier = applier;
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public PersistCounts persist(Feed feed, List<ParsedEntry> entries, Instant fetchedAt) {
        long sourceId = feed.getSource().getId();
        long feedId = feed.getId();
        String sourceKey = feed.getSource().getKey();

        telemetry.lockWait(sourceKey, sourceId, () -> sourceLock.acquire(sourceId));
        requireFeedStillIn(feedId, sourceId);

        // Re-read under the lock: a PATCH may have changed the homepage since the feed was loaded.
        String homepageUrl = jdbc.sql("SELECT homepage_url FROM source WHERE id = :id")
                .param("id", sourceId)
                .query(String.class)
                .optional()
                .orElse(null);
        String homepageKey = EntryKeys.linkKey(homepageUrl);

        DedupInput input = new DedupInput(feedId, entries, homepageKey, fetchedAt, loader.forFeed(sourceId, feedId));
        Resolution resolution = telemetry.span(IngestTelemetry.RESOLVE_SPAN,
                Map.of(IngestTelemetry.SOURCE_ID_ATTRIBUTE, String.valueOf(sourceId)),
                () -> resolver.resolve(input));

        return applier.apply(sourceId, feedId, resolution, fetchedAt, clock.instant());
    }

    /**
     * A move or merge may have changed the feed's source since it was loaded, and only the lock makes this read
     * reliable. A mismatch rolls the transaction back so the caller can retry with the feed re-read. A feed that is
     * gone is left to fail on its foreign key.
     */
    private void requireFeedStillIn(long feedId, long sourceId) {
        Long current = jdbc.sql("SELECT source_id FROM feed WHERE id = :id")
                .param("id", feedId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (current != null && current != sourceId) {
            throw new FeedSourceChangedException(feedId);
        }
    }
}
