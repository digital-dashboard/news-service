package com.j11a.argus.article;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Plain SQL rather than JPA: the unique key decides, atomically, whether an entry is new. */
@Component
public class ArticleInserter {

    private static final String INSERT_ARTICLE = """
            INSERT INTO article (source_id, guid_key, raw_guid, link_key, link, title, excerpt, author, image_url,
                                 categories, published_at, updated_at_upstream, effective_at, fetched_at, modified_at)
            VALUES (:sourceId, :guidKey, :rawGuid, :linkKey, :link, :title, :excerpt, :author, :imageUrl,
                    :categories, :publishedAt, :updatedAtUpstream, :effectiveAt, :fetchedAt, :modifiedAt)
            ON CONFLICT (source_id, guid_key) DO NOTHING
            RETURNING id
            """;
    private static final String SELECT_EXISTING = "SELECT id FROM article WHERE source_id = :sourceId AND guid_key = :guidKey";
    private static final String LINK_FEED = """
            INSERT INTO article_feed (article_id, feed_id, first_seen_at)
            VALUES (:articleId, :feedId, :now)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ArticleInserter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public InsertOutcome insert(NewArticle article, long feedId) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Optional<Long> inserted = jdbc.sql(INSERT_ARTICLE)
                .param("sourceId", article.sourceId())
                .param("guidKey", article.guidKey())
                .param("rawGuid", article.rawGuid())
                .param("linkKey", article.linkKey())
                .param("link", article.link())
                .param("title", article.title())
                .param("excerpt", article.excerpt())
                .param("author", article.author())
                .param("imageUrl", article.imageUrl())
                .param("categories", article.categories().toArray(String[]::new))
                .param("publishedAt", utc(article.publishedAt()))
                .param("updatedAtUpstream", utc(article.updatedAtUpstream()))
                .param("effectiveAt", utc(article.effectiveAt()))
                .param("fetchedAt", utc(article.fetchedAt()))
                .param("modifiedAt", now)
                .query(Long.class)
                .optional();
        long articleId = inserted.orElseGet(() -> existingId(article));
        jdbc.sql(LINK_FEED).param("articleId", articleId).param("feedId", feedId).param("now", now).update();
        return inserted.isPresent() ? InsertOutcome.INSERTED : InsertOutcome.UNCHANGED;
    }

    private long existingId(NewArticle article) {
        return jdbc.sql(SELECT_EXISTING)
                .param("sourceId", article.sourceId())
                .param("guidKey", article.guidKey())
                .query(Long.class)
                .single();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
