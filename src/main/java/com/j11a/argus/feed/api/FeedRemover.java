package com.j11a.argus.feed.api;

import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.web.error.ApiException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes a feed and the articles only it linked to, in one transaction. The source row is kept. */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FeedRemover {

    private static final String SOURCE_OF_FEED = "SELECT source_id FROM feed WHERE id = :id";
    private static final String DELETE_FEED = "DELETE FROM feed WHERE id = :id";
    private static final String DELETE_OWNED_ARTICLES = """
            DELETE FROM article a USING article_feed mine
            WHERE mine.feed_id = :feedId AND mine.article_id = a.id
              AND NOT EXISTS (SELECT 1 FROM article_feed o WHERE o.article_id = a.id AND o.feed_id <> :feedId)
            """;

    private final JdbcClient jdbc;
    private final SourceLock sourceLock;
    private final FeedHealthGauges healthGauges;
    private final TransactionTemplate transaction;

    FeedRemover(JdbcClient jdbc, SourceLock sourceLock, FeedHealthGauges healthGauges,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.sourceLock = sourceLock;
        this.healthGauges = healthGauges;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Not transactional itself, so a source change under the lock can roll back and be retried. */
    void delete(long id) {
        SourceChangeRetry.twice(id, "deleted", () -> transaction.executeWithoutResult(status -> deleteLocked(id)));
    }

    private void deleteLocked(long id) {
        long sourceId = sourceOf(id);
        // Serialises with ingest so a delete cannot race an article_feed insert for the same source.
        sourceLock.acquire(sourceId);
        // A move or merge may have taken the feed elsewhere since the read; only the lock makes this read binding.
        if (sourceOf(id) != sourceId) {
            throw new FeedSourceChangedException(id);
        }
        int removedArticles = jdbc.sql(DELETE_OWNED_ARTICLES).param("feedId", id).update();
        int deleted = jdbc.sql(DELETE_FEED).param("id", id).update();
        if (deleted == 0) {
            throw ApiException.feedNotFound(id);
        }
        healthGauges.refreshAfterCommit();
        log.atInfo()
                .setMessage(FeedService.feedText(id, "deleted along with " + removedArticles + " articles"))
                .addKeyValue(LogKeys.FEED_ID, id)
                .addKeyValue(LogKeys.SOURCE_ID, sourceId)
                .addKeyValue(LogKeys.ARTICLES_REMOVED, removedArticles)
                .log();
    }

    private long sourceOf(long feedId) {
        Optional<Long> sourceId = jdbc.sql(SOURCE_OF_FEED).param("id", feedId).query(Long.class).optional();
        return sourceId.orElseThrow(() -> ApiException.feedNotFound(feedId));
    }
}
