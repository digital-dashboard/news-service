package com.j11a.argus.article;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ArticleWriter {

    /** inserted is true for a fresh row: xmax = 0 tells it apart from the ON CONFLICT path, which rewrites the row. */
    public record WriteResult(long id, boolean inserted) {}

    private static final String INSERT_ARTICLE = """
            INSERT INTO article (source_id, guid_key, raw_guid, link_key, link, content_hash, title, excerpt, author,
                                 image_url, categories, published_at, updated_at_upstream, effective_at, fetched_at,
                                 modified_at)
            VALUES (:sourceId, :guidKey, :rawGuid, :linkKey, :link, :contentHash, :title, :excerpt, :author, :imageUrl,
                    :categories, :publishedAt, :updatedAtUpstream, :effectiveAt, :fetchedAt, :modifiedAt)
            ON CONFLICT (source_id, guid_key) DO UPDATE SET modified_at = EXCLUDED.modified_at
            RETURNING id, (xmax = 0) AS inserted
            """;

    // COALESCE keeps stored values that a feed omits; effective_at only moves forward, and only for dated entries.
    private static final String REWRITE_CONTENT = """
            UPDATE article SET link_key = COALESCE(:linkKey, link_key), link = COALESCE(:link, link),
              title = :title, excerpt = :excerpt, author = COALESCE(:author, author),
              image_url = COALESCE(:imageUrl, image_url), categories = :categories,
              published_at = COALESCE(published_at, :publishedAt),
              updated_at_upstream = GREATEST(updated_at_upstream, :updatedAtUpstream),
              effective_at = CASE WHEN :dated THEN GREATEST(effective_at, :effectiveAt) ELSE effective_at END,
              content_hash = :contentHash, modified_at = :now
            WHERE id = :id
            """;

    private static final String ADVANCE_TIMESTAMPS = """
            UPDATE article SET updated_at_upstream = GREATEST(updated_at_upstream, :updatedAtUpstream),
              effective_at = CASE WHEN :dated THEN GREATEST(effective_at, :effectiveAt) ELSE effective_at END,
              modified_at = :now
            WHERE id = :id
            """;

    private static final String REPLACE_GUID = """
            UPDATE article SET guid_key = :guidKey, raw_guid = :rawGuid, modified_at = :now WHERE id = :id
            """;

    // IS DISTINCT FROM skips the write when the feed's hash has not changed.
    private static final String LINK_FEED = """
            INSERT INTO article_feed (article_id, feed_id, first_seen_at, content_hash)
            VALUES (:articleId, :feedId, :now, :contentHash)
            ON CONFLICT (article_id, feed_id) DO UPDATE SET content_hash = EXCLUDED.content_hash
            WHERE article_feed.content_hash IS DISTINCT FROM EXCLUDED.content_hash
            """;

    private final JdbcClient jdbc;

    public ArticleWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WriteResult insert(NewArticle a, OffsetDateTime now) {
        return jdbc.sql(INSERT_ARTICLE)
                .param("sourceId", a.sourceId())
                .param("guidKey", a.guidKey())
                .param("rawGuid", a.rawGuid())
                .param("linkKey", a.linkKey())
                .param("link", a.link())
                .param("contentHash", a.contentHash())
                .param("title", a.title())
                .param("excerpt", a.excerpt())
                .param("author", a.author())
                .param("imageUrl", a.imageUrl())
                .param("categories", a.categories().toArray(String[]::new))
                .param("publishedAt", utc(a.publishedAt()))
                .param("updatedAtUpstream", utc(a.updatedAtUpstream()))
                .param("effectiveAt", utc(a.effectiveAt()))
                .param("fetchedAt", utc(a.fetchedAt()))
                .param("modifiedAt", now)
                .query((rs, rowNum) -> new WriteResult(rs.getLong("id"), rs.getBoolean("inserted")))
                .single();
    }

    public void rewriteContent(ArticleEdit e, OffsetDateTime now) {
        jdbc.sql(REWRITE_CONTENT)
                .param("id", e.id())
                .param("linkKey", e.linkKey())
                .param("link", e.link())
                .param("title", e.title())
                .param("excerpt", e.excerpt())
                .param("author", e.author())
                .param("imageUrl", e.imageUrl())
                .param("categories", e.categories().toArray(String[]::new))
                .param("publishedAt", utc(e.publishedAt()))
                .param("updatedAtUpstream", utc(e.updatedAtUpstream()))
                .param("effectiveAt", utc(e.effectiveAt()))
                .param("dated", e.dated())
                .param("contentHash", e.contentHash())
                .param("now", now)
                .update();
    }

    public void advanceTimestamps(long id, @Nullable Instant updatedAtUpstream, Instant effectiveAt, boolean dated,
            OffsetDateTime now) {
        jdbc.sql(ADVANCE_TIMESTAMPS)
                .param("id", id)
                .param("updatedAtUpstream", utc(updatedAtUpstream))
                .param("effectiveAt", utc(effectiveAt))
                .param("dated", dated)
                .param("now", now)
                .update();
    }

    public void replaceGuid(long id, String guidKey, @Nullable String rawGuid, OffsetDateTime now) {
        jdbc.sql(REPLACE_GUID)
                .param("id", id)
                .param("guidKey", guidKey)
                .param("rawGuid", rawGuid)
                .param("now", now)
                .update();
    }

    public void link(long articleId, long feedId, String contentHash, OffsetDateTime now) {
        jdbc.sql(LINK_FEED)
                .param("articleId", articleId)
                .param("feedId", feedId)
                .param("now", now)
                .param("contentHash", contentHash)
                .update();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
