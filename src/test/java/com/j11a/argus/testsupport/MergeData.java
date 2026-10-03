package com.j11a.argus.testsupport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts sources, feeds, articles and links straight into the tables, for merge and collapse tests. */
public final class MergeData {

    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");

    private final JdbcClient jdbc;

    public MergeData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public long source(String key, @Nullable String homepage) {
        return jdbc.sql("""
                INSERT INTO source (key, name, homepage_url, created_at, updated_at)
                VALUES (:key, :key, :homepage, :now, :now) RETURNING id
                """)
                .param("key", key).param("homepage", homepage).param("now", at(NOW))
                .query(Long.class).single();
    }

    public long feed(long sourceId, String url) {
        return jdbc.sql("""
                INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
                VALUES (:source, :url, :url, 'NEWS', true, :now, :now) RETURNING id
                """)
                .param("source", sourceId).param("url", url).param("now", at(NOW))
                .query(Long.class).single();
    }

    public long article(long sourceId, String guidKey, @Nullable String linkKey, Instant fetchedAt) {
        return jdbc.sql("""
                INSERT INTO article (source_id, guid_key, raw_guid, link_key, link, content_hash, title, excerpt,
                                     author, categories, effective_at, fetched_at, modified_at)
                VALUES (:source, :guid, :guid, :linkKey, :linkKey, 'hash-' || :guid, 'Title ' || :guid, 'Excerpt',
                        'Author', ARRAY['news']::text[], :fetched, :fetched, :fetched)
                RETURNING id
                """)
                .param("source", sourceId).param("guid", guidKey).param("linkKey", linkKey)
                .param("fetched", at(fetchedAt))
                .query(Long.class).single();
    }

    public void link(long articleId, long feedId, Instant firstSeenAt, @Nullable String contentHash) {
        jdbc.sql("""
                INSERT INTO article_feed (article_id, feed_id, first_seen_at, content_hash)
                VALUES (:article, :feed, :seen, :hash)
                """)
                .param("article", articleId).param("feed", feedId).param("seen", at(firstSeenAt))
                .param("hash", contentHash)
                .update();
    }
}
