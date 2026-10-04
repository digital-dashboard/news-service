package com.j11a.argus.article;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Folds duplicate articles into their survivors on a plain JDBC connection, so a Liquibase change can use it.
 * Extension point for per-article data that must follow a survivor.
 */
public final class ArticleCollapser {

    public record Loser(long id, long survivorId) {
    }

    // Grouped by (survivor, feed) first: two losers on one survivor and feed would make ON CONFLICT hit one row twice.
    private static final String FOLD_LINKS = """
            INSERT INTO article_feed (article_id, feed_id, first_seen_at, content_hash)
            SELECT m.survivor, af.feed_id, MIN(af.first_seen_at),
                   (array_agg(af.content_hash ORDER BY af.first_seen_at, af.feed_id))[1]
            FROM unnest(?::bigint[], ?::bigint[]) AS m(loser, survivor)
            JOIN article_feed af ON af.article_id = m.loser
            WHERE af.feed_id = COALESCE(?::bigint, af.feed_id)
            GROUP BY m.survivor, af.feed_id
            ON CONFLICT (article_id, feed_id) DO UPDATE
            SET first_seen_at = LEAST(article_feed.first_seen_at, EXCLUDED.first_seen_at),
                content_hash = COALESCE(article_feed.content_hash, EXCLUDED.content_hash)
            """;

    private static final String DELETE_ARTICLES = "DELETE FROM article WHERE id = ANY(?)";

    /** Folds the losers' links into their survivors, then deletes the losers. Returns the links written. */
    public int collapse(Connection c, List<Loser> losers) throws SQLException {
        int folded = foldLinks(c, losers);
        deleteArticles(c, losers.stream().map(Loser::id).toList());
        return folded;
    }

    /** Folds every link of each loser into its survivor, and returns how many survivor links were written. */
    public int foldLinks(Connection c, List<Loser> losers) throws SQLException {
        return foldLinks(c, losers, null);
    }

    /** As {@link #foldLinks(Connection, List)}, but only the links of one feed when feedId is given. */
    public int foldLinks(Connection c, List<Loser> losers, @Nullable Long feedId) throws SQLException {
        if (losers.isEmpty()) {
            return 0;
        }
        Long[] loserIds = losers.stream().map(Loser::id).toArray(Long[]::new);
        Long[] survivorIds = losers.stream().map(Loser::survivorId).toArray(Long[]::new);
        try (PreparedStatement ps = c.prepareStatement(FOLD_LINKS)) {
            ps.setArray(1, c.createArrayOf("bigint", loserIds));
            ps.setArray(2, c.createArrayOf("bigint", survivorIds));
            ps.setObject(3, feedId);
            return ps.executeUpdate();
        }
    }

    /** Deletes the articles; fold their links first, because article_feed rows cascade. */
    public void deleteArticles(Connection c, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(DELETE_ARTICLES)) {
            Array array = c.createArrayOf("bigint", ids.toArray(Long[]::new));
            ps.setArray(1, array);
            ps.executeUpdate();
        }
    }
}
