package com.j11a.argus.source;

import com.j11a.argus.article.ArticleCollapser;
import com.j11a.argus.article.ArticleCollapser.Loser;
import com.j11a.argus.article.ArticleDuplicates;
import com.j11a.argus.article.ArticleDuplicates.Mode;
import com.j11a.argus.article.ArticleDuplicates.Row;
import com.j11a.argus.config.Clocks;
import com.j11a.argus.ingest.EntryKeys;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a source into another, or moves one feed between sources. Both take the two source locks in ascending id,
 * then (re)read what they depend on, so an overlapping merge, move or ingest sees the committed result.
 */
@Slf4j
@Service
public class SourceMerger {

    record SourceRow(long id, String key, @Nullable String homepageUrl) {
    }

    private static final String IDS = "ids";
    private static final String FEED = "feed";
    private static final String SOURCE = "source";
    private static final String TARGET = "target";

    private static final String READ_SOURCE = "SELECT id, key, homepage_url FROM source WHERE id = :id";
    private static final String READ_FEED_SOURCE = "SELECT source_id FROM feed WHERE id = :id";

    private static final String SOURCE_ARTICLES = """
            SELECT id, source_id, guid_key, link_key, fetched_at FROM article WHERE source_id = :source
            """;

    private static final String FEED_ARTICLES = """
            SELECT a.id, a.source_id, a.guid_key, a.link_key, a.fetched_at
            FROM article a JOIN article_feed af ON af.article_id = a.id
            WHERE af.feed_id = :feed AND a.source_id = :source
            """;

    private static final String MOVE_ARTICLES = "UPDATE article SET source_id = :target WHERE source_id = :source";

    private static final String MOVE_FEEDS = """
            UPDATE feed SET source_id = :target, updated_at = :now WHERE source_id = :source
            """;

    private static final String DELETE_SOURCE = "DELETE FROM source WHERE id = :id";

    private static final String MOVE_FEED = "UPDATE feed SET source_id = :target, updated_at = :now WHERE id = :feed";

    private static final String REMOVE_FEED_LINKS = """
            DELETE FROM article_feed WHERE feed_id = :feed AND article_id = ANY(:ids)
            """;

    private static final String DELETE_UNLINKED = """
            DELETE FROM article a
            WHERE id = ANY(:ids) AND NOT EXISTS (SELECT 1 FROM article_feed af WHERE af.article_id = a.id)
            """;

    private static final String LINKED_TO_OTHER_FEEDS = """
            SELECT DISTINCT article_id FROM article_feed WHERE article_id = ANY(:ids) AND feed_id <> :feed
            """;

    private static final String MOVE_EXCLUSIVE = "UPDATE article SET source_id = :target WHERE id = ANY(:ids)";

    // The copy is matched back to its original by guid_key, which is unique within each source.
    private static final String COPY_SHARED = """
            WITH copies AS (
                INSERT INTO article (source_id, guid_key, raw_guid, link_key, link, content_hash, title, excerpt,
                                     author, image_url, categories, published_at, updated_at_upstream, effective_at,
                                     fetched_at, modified_at)
                SELECT :target, guid_key, raw_guid, link_key, link, content_hash, title, excerpt, author, image_url,
                       categories, published_at, updated_at_upstream, effective_at, fetched_at, modified_at
                FROM article WHERE id = ANY(:ids)
                RETURNING id, guid_key
            )
            INSERT INTO article_feed (article_id, feed_id, first_seen_at, content_hash)
            SELECT c.id, :feed, af.first_seen_at, af.content_hash
            FROM copies c
            JOIN article o ON o.source_id = :source AND o.guid_key = c.guid_key
            JOIN article_feed af ON af.article_id = o.id AND af.feed_id = :feed
            """;

    private static final String DELETE_SOURCE_IF_EMPTY = """
            DELETE FROM source s
            WHERE s.id = :id
              AND NOT EXISTS (SELECT 1 FROM feed WHERE source_id = s.id)
              AND NOT EXISTS (SELECT 1 FROM article WHERE source_id = s.id)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final SourceLock sourceLock;
    private final Clock clock;
    private final MergeTelemetry telemetry;
    private final ArticleCollapser collapser = new ArticleCollapser();

    public SourceMerger(JdbcClient jdbc, JdbcTemplate jdbcTemplate, SourceLock sourceLock, Clock clock,
            MergeTelemetry telemetry) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.sourceLock = sourceLock;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Transactional
    public SourceMergeResponse merge(long sourceId, long targetSourceId) {
        if (sourceId == targetSourceId) {
            throw new ApiException(ErrorCode.SOURCE_MERGE_INVALID,
                    "Source " + sourceId + " cannot be merged into itself.");
        }
        return telemetry.merge(sourceId, targetSourceId, () -> mergeLocked(sourceId, targetSourceId));
    }

    /**
     * Moves the feed and its articles to the target source. An article shared with feeds that stay is copied, so
     * those feeds keep theirs; a source left with no feeds and no articles is deleted.
     */
    @Transactional
    public MoveResult moveFeed(long feedId, long targetSourceId) {
        long current = feedSourceId(feedId);
        if (current == targetSourceId) {
            return MoveResult.unchanged(feedId, current);
        }
        return telemetry.feedMove(feedId, current, targetSourceId, () -> moveLocked(feedId, current, targetSourceId));
    }

    private SourceMergeResponse mergeLocked(long sourceId, long targetSourceId) {
        // The advisory lock key is an int, so an id that cannot exist must be refused before it is locked.
        requireSource(sourceId);
        requireSource(targetSourceId);
        lockInOrder(sourceId, targetSourceId);
        SourceRow source = requireSource(sourceId);
        SourceRow target = requireSource(targetSourceId);

        List<Loser> losers = ArticleDuplicates.find(
                articleRows(SOURCE_ARTICLES, sourceId, null),
                articleRows(SOURCE_ARTICLES, targetSourceId, null),
                homepageKeys(source, target),
                Mode.MERGE);
        int linksFolded = foldAndDelete(losers);

        int articlesMoved = jdbc.sql(MOVE_ARTICLES).param(TARGET, targetSourceId).param(SOURCE, sourceId).update();
        int feedsMoved = jdbc.sql(MOVE_FEEDS)
                .param(TARGET, targetSourceId).param("now", now()).param(SOURCE, sourceId).update();
        jdbc.sql(DELETE_SOURCE).param("id", sourceId).update();

        SourceMergeResponse response = new SourceMergeResponse(
                sourceId, targetSourceId, feedsMoved, articlesMoved, losers.size(), linksFolded);
        logMerged(source, target, response);
        return response;
    }

    private MoveResult moveLocked(long feedId, long expectedSourceId, long targetSourceId) {
        requireSource(targetSourceId);
        lockInOrder(expectedSourceId, targetSourceId);
        long sourceId = feedSourceId(feedId);
        if (sourceId == targetSourceId) {
            return MoveResult.unchanged(feedId, sourceId);
        }
        if (sourceId != expectedSourceId) {
            throw new FeedSourceChangedException(feedId);
        }
        SourceRow source = requireSource(sourceId);
        SourceRow target = requireSource(targetSourceId);

        List<Row> feedArticles = articleRows(FEED_ARTICLES, sourceId, feedId);
        List<Loser> losers = ArticleDuplicates.find(
                feedArticles, articleRows(SOURCE_ARTICLES, targetSourceId, null), homepageKeys(source, target),
                Mode.MOVE);
        int linksFolded = absorbIntoTarget(feedId, losers);

        Set<Long> collapsed = new HashSet<>();
        losers.forEach(loser -> collapsed.add(loser.id()));
        List<Long> remaining = feedArticles.stream().map(Row::id).filter(id -> !collapsed.contains(id)).toList();
        Set<Long> shared = idsLinkedToOtherFeeds(remaining, feedId);
        List<Long> exclusive = remaining.stream().filter(id -> !shared.contains(id)).toList();

        int moved = exclusive.isEmpty() ? 0 : jdbc.sql(MOVE_EXCLUSIVE)
                .param(TARGET, targetSourceId).param(IDS, longs(exclusive)).update();
        int copied = shared.isEmpty() ? 0 : copyShared(feedId, sourceId, targetSourceId, List.copyOf(shared));
        jdbc.sql(MOVE_FEED).param(TARGET, targetSourceId).param("now", now()).param(FEED, feedId).update();
        boolean sourceDeleted = jdbc.sql(DELETE_SOURCE_IF_EMPTY).param("id", sourceId).update() > 0;

        MoveResult result = new MoveResult(
                feedId, sourceId, targetSourceId, moved, copied, losers.size(), linksFolded, sourceDeleted);
        logMoved(source, target, result);
        return result;
    }

    private void lockInOrder(long first, long second) {
        sourceLock.acquire(Math.min(first, second));
        sourceLock.acquire(Math.max(first, second));
    }

    private SourceRow requireSource(long id) {
        return jdbc.sql(READ_SOURCE).param("id", id)
                .query((rs, rowNum) -> new SourceRow(rs.getLong("id"), rs.getString("key"),
                        rs.getString("homepage_url")))
                .optional()
                .orElseThrow(() -> ApiException.sourceNotFound(id));
    }

    private long feedSourceId(long feedId) {
        return jdbc.sql(READ_FEED_SOURCE).param("id", feedId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.feedNotFound(feedId));
    }

    private List<Row> articleRows(String sql, long sourceId, @Nullable Long feedId) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param(SOURCE, sourceId);
        if (feedId != null) {
            statement = statement.param(FEED, feedId);
        }
        return statement.query((rs, rowNum) -> new Row(
                rs.getLong("id"),
                rs.getLong("source_id"),
                rs.getString("guid_key"),
                rs.getString("link_key"),
                rs.getObject("fetched_at", OffsetDateTime.class).toInstant())).list();
    }

    private static Set<String> homepageKeys(SourceRow first, SourceRow second) {
        Set<String> keys = new HashSet<>();
        for (SourceRow source : List.of(first, second)) {
            String key = EntryKeys.linkKey(source.homepageUrl());
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private int foldAndDelete(List<Loser> losers) {
        return Objects.requireNonNull(
                jdbcTemplate.execute((ConnectionCallback<Integer>) c -> collapser.collapse(c, losers)));
    }

    /**
     * Folds only this feed's link into each target survivor and removes it from the source article, which goes only
     * once no other feed links it.
     */
    private int absorbIntoTarget(long feedId, List<Loser> losers) {
        if (losers.isEmpty()) {
            return 0;
        }
        int folded = Objects.requireNonNull(jdbcTemplate.execute(
                (ConnectionCallback<Integer>) c -> collapser.foldLinks(c, losers, feedId)));
        Long[] loserIds = longs(losers.stream().map(Loser::id).toList());
        jdbc.sql(REMOVE_FEED_LINKS).param(FEED, feedId).param(IDS, loserIds).update();
        jdbc.sql(DELETE_UNLINKED).param(IDS, loserIds).update();
        return folded;
    }

    private Set<Long> idsLinkedToOtherFeeds(List<Long> articleIds, long feedId) {
        if (articleIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(LINKED_TO_OTHER_FEEDS)
                .param(IDS, longs(articleIds)).param(FEED, feedId).query(Long.class).list());
    }

    private int copyShared(long feedId, long sourceId, long targetSourceId, List<Long> articleIds) {
        int copied = jdbc.sql(COPY_SHARED)
                .param(TARGET, targetSourceId).param(SOURCE, sourceId).param(FEED, feedId)
                .param(IDS, longs(articleIds)).update();
        jdbc.sql(REMOVE_FEED_LINKS).param(FEED, feedId).param(IDS, longs(articleIds)).update();
        return copied;
    }

    private static Long[] longs(List<Long> ids) {
        return ids.toArray(Long[]::new);
    }

    private OffsetDateTime now() {
        return Clocks.utcNow(clock);
    }

    private static void logMerged(SourceRow source, SourceRow target, SourceMergeResponse r) {
        log.atInfo()
                .addKeyValue(LogKeys.SOURCE_ID, r.sourceId())
                .addKeyValue(LogKeys.SOURCE_KEY, source.key())
                .addKeyValue(LogKeys.TARGET_SOURCE_ID, r.targetSourceId())
                .addKeyValue(LogKeys.FEEDS_MOVED, r.feedsMoved())
                .addKeyValue(LogKeys.ARTICLES_MOVED, r.articlesMoved())
                .addKeyValue(LogKeys.ARTICLES_COLLAPSED, r.articlesCollapsed())
                .addKeyValue(LogKeys.LINKS_FOLDED, r.linksFolded())
                .setMessage("Source " + r.sourceId() + " (" + source.key() + ") merged into " + r.targetSourceId()
                        + " (" + target.key() + "): " + r.feedsMoved() + " feeds and " + r.articlesMoved()
                        + " articles moved, " + r.articlesCollapsed() + " collapsed")
                .log();
    }

    private static void logMoved(SourceRow source, SourceRow target, MoveResult r) {
        log.atInfo()
                .addKeyValue(LogKeys.FEED_ID, r.feedId())
                .addKeyValue(LogKeys.SOURCE_ID, r.sourceId())
                .addKeyValue(LogKeys.SOURCE_KEY, source.key())
                .addKeyValue(LogKeys.TARGET_SOURCE_ID, r.targetSourceId())
                .addKeyValue(LogKeys.ARTICLES_MOVED, r.articlesMoved())
                .addKeyValue(LogKeys.ARTICLES_COPIED, r.articlesCopied())
                .addKeyValue(LogKeys.ARTICLES_COLLAPSED, r.articlesCollapsed())
                .addKeyValue(LogKeys.LINKS_FOLDED, r.linksFolded())
                .addKeyValue(LogKeys.SOURCE_DELETED, r.sourceDeleted())
                .setMessage("Feed " + r.feedId() + " moved from source " + r.sourceId() + " (" + source.key()
                        + ") to " + r.targetSourceId() + " (" + target.key() + "): " + r.articlesMoved()
                        + " articles moved, " + r.articlesCopied() + " copied, " + r.articlesCollapsed()
                        + " collapsed" + (r.sourceDeleted() ? "; the source was left empty and deleted" : ""))
                .log();
    }
}
