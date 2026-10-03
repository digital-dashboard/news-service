package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.article.ArticleEdit;
import com.j11a.argus.article.ArticleWriter;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArticleWriterIT extends AbstractIntegrationTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 10, 2, 11, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime T2 = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime T3 = OffsetDateTime.of(2026, 10, 2, 13, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private ArticleWriter writer;

    private long sourceId;
    private long feedId;

    @BeforeEach
    void setUpSourceAndFeed() {
        FeedResponse feed = createFeedFrom("/writer/feed.xml", "bbc-like-rss2.xml", Topic.WORLD);
        feedId = feed.id();
        sourceId = jdbcClient.sql("SELECT source_id FROM feed WHERE id = :id")
                .param("id", feedId)
                .query(Long.class)
                .single();
    }

    private NewArticle sampleArticle(String guidKey, @Nullable Instant publishedAt, Instant effectiveAt, Instant fetchedAt) {
        return new NewArticle(
                sourceId,
                guidKey,
                guidKey,
                "https://example.test/" + guidKey,
                "https://example.test/" + guidKey,
                "hash-initial",
                "Initial Title",
                "Initial Excerpt",
                "Alice",
                "https://img.test/pic.jpg",
                List.of("news"),
                publishedAt,
                null,
                effectiveAt,
                fetchedAt);
    }

    private OffsetDateTime articleTime(String column, long id) {
        return jdbcClient.sql("SELECT " + column + " FROM article WHERE id = :id")
                .param("id", id)
                .query(OffsetDateTime.class).single();
    }

    private String linkRowVersion(long articleId) {
        return jdbcClient.sql("SELECT xmin::text FROM article_feed WHERE article_id = :aid AND feed_id = :fid")
                .param("aid", articleId)
                .param("fid", feedId)
                .query(String.class).single();
    }

    private OffsetDateTime firstSeenAt(long articleId) {
        return jdbcClient.sql("SELECT first_seen_at FROM article_feed WHERE article_id = :aid AND feed_id = :fid")
                .param("aid", articleId)
                .param("fid", feedId)
                .query(OffsetDateTime.class).single();
    }

    @Test
    void rewriteContentKeepsFetchedAtAndUpdatesModifiedAt() {
        NewArticle initial = sampleArticle("art-1", T0.toInstant(), T0.toInstant(), T0.toInstant());
        ArticleWriter.WriteResult result = writer.insert(initial, T0);
        assertThat(result.inserted()).isTrue();

        ArticleEdit edit = new ArticleEdit(
                result.id(),
                "https://example.test/art-1",
                "https://example.test/art-1",
                "Updated Title",
                "Updated Excerpt",
                "Bob",
                "https://img.test/pic2.jpg",
                List.of("news", "tech"),
                T1.toInstant(),
                T1.toInstant(),
                T1.toInstant(),
                true,
                "hash-updated");

        writer.rewriteContent(edit, T1);

        Map<String, Object> row = jdbcClient.sql("SELECT title FROM article WHERE id = :id")
                .param("id", result.id())
                .query().singleRow();

        assertThat(articleTime("fetched_at", result.id())).isEqualTo(T0);
        assertThat(articleTime("modified_at", result.id())).isEqualTo(T1);
        assertThat(row).containsEntry("title", "Updated Title");
    }

    @Test
    void nullAuthorOrImageDoesNotEraseStoredOne() {
        NewArticle initial = sampleArticle("art-2", T0.toInstant(), T0.toInstant(), T0.toInstant());
        ArticleWriter.WriteResult result = writer.insert(initial, T0);

        ArticleEdit edit = new ArticleEdit(
                result.id(),
                initial.linkKey(),
                initial.link(),
                "New Title",
                "New Excerpt",
                null,
                null,
                List.of("news"),
                T0.toInstant(),
                null,
                T0.toInstant(),
                true,
                "hash-2");

        writer.rewriteContent(edit, T1);

        Map<String, Object> row = jdbcClient.sql("SELECT author, image_url FROM article WHERE id = :id")
                .param("id", result.id())
                .query().singleRow();

        assertThat(row)
                .containsEntry("author", "Alice")
                .containsEntry("image_url", "https://img.test/pic.jpg");
    }

    @Test
    void effectiveAtNeverDecreasesAndUndatedEntryNeverBumpsIt() {
        NewArticle initial = sampleArticle("art-3", T1.toInstant(), T2.toInstant(), T0.toInstant());
        ArticleWriter.WriteResult result = writer.insert(initial, T0);

        ArticleEdit editOlder = new ArticleEdit(
                result.id(),
                initial.linkKey(),
                initial.link(),
                initial.title(),
                initial.excerpt(),
                initial.author(),
                initial.imageUrl(),
                initial.categories(),
                T1.toInstant(),
                null,
                T1.toInstant(),
                true,
                "hash-3");
        writer.rewriteContent(editOlder, T1);

        OffsetDateTime effective1 = jdbcClient.sql("SELECT effective_at FROM article WHERE id = :id")
                .param("id", result.id())
                .query(OffsetDateTime.class).single();
        assertThat(effective1).isEqualTo(T2);

        ArticleEdit editUndated = new ArticleEdit(
                result.id(),
                initial.linkKey(),
                initial.link(),
                initial.title(),
                initial.excerpt(),
                initial.author(),
                initial.imageUrl(),
                initial.categories(),
                null,
                null,
                T3.toInstant(),
                false,
                "hash-4");
        writer.rewriteContent(editUndated, T2);

        OffsetDateTime effective2 = jdbcClient.sql("SELECT effective_at FROM article WHERE id = :id")
                .param("id", result.id())
                .query(OffsetDateTime.class).single();
        assertThat(effective2).isEqualTo(T2);

        writer.advanceTimestamps(result.id(), null, T3.toInstant(), false, T2);
        OffsetDateTime effective3 = jdbcClient.sql("SELECT effective_at FROM article WHERE id = :id")
                .param("id", result.id())
                .query(OffsetDateTime.class).single();
        assertThat(effective3).isEqualTo(T2);

        writer.advanceTimestamps(result.id(), T3.toInstant(), T3.toInstant(), true, T3);
        OffsetDateTime effective4 = jdbcClient.sql("SELECT effective_at FROM article WHERE id = :id")
                .param("id", result.id())
                .query(OffsetDateTime.class).single();
        assertThat(effective4).isEqualTo(T3);
    }

    @Test
    void publishedAtKeepsStoredValueWhenNonNull() {
        NewArticle initial = sampleArticle("art-4", T0.toInstant(), T0.toInstant(), T0.toInstant());
        ArticleWriter.WriteResult result = writer.insert(initial, T0);

        ArticleEdit editWithDifferentPublished = new ArticleEdit(
                result.id(),
                initial.linkKey(),
                initial.link(),
                "Title",
                "Excerpt",
                "Author",
                null,
                List.of(),
                T3.toInstant(),
                null,
                T3.toInstant(),
                true,
                "hash-5");

        writer.rewriteContent(editWithDifferentPublished, T1);

        OffsetDateTime published = jdbcClient.sql("SELECT published_at FROM article WHERE id = :id")
                .param("id", result.id())
                .query(OffsetDateTime.class).single();
        assertThat(published).isEqualTo(T0);
    }

    @Test
    void linkRefreshesHashOnlyWhenItDiffers() {
        NewArticle initial = sampleArticle("art-5", T0.toInstant(), T0.toInstant(), T0.toInstant());
        ArticleWriter.WriteResult result = writer.insert(initial, T0);

        writer.link(result.id(), feedId, "hash-a", T0);

        Map<String, Object> linkRow1 = jdbcClient.sql("SELECT first_seen_at, content_hash FROM article_feed WHERE article_id = :aid AND feed_id = :fid")
                .param("aid", result.id())
                .param("fid", feedId)
                .query().singleRow();
        assertThat(firstSeenAt(result.id())).isEqualTo(T0);
        assertThat(linkRow1).containsEntry("content_hash", "hash-a");

        String xminBefore = linkRowVersion(result.id());

        // Same hash with later time: no write at all, so first_seen_at and the row version are unchanged
        writer.link(result.id(), feedId, "hash-a", T1);
        assertThat(linkRowVersion(result.id())).isEqualTo(xminBefore);
        Map<String, Object> linkRow2 = jdbcClient.sql("SELECT first_seen_at, content_hash FROM article_feed WHERE article_id = :aid AND feed_id = :fid")
                .param("aid", result.id())
                .param("fid", feedId)
                .query().singleRow();
        assertThat(firstSeenAt(result.id())).isEqualTo(T0);
        assertThat(linkRow2).containsEntry("content_hash", "hash-a");

        writer.link(result.id(), feedId, "hash-b", T2);
        Map<String, Object> linkRow3 = jdbcClient.sql("SELECT first_seen_at, content_hash FROM article_feed WHERE article_id = :aid AND feed_id = :fid")
                .param("aid", result.id())
                .param("fid", feedId)
                .query().singleRow();
        assertThat(firstSeenAt(result.id())).isEqualTo(T0);
        assertThat(linkRow3).containsEntry("content_hash", "hash-b");
    }
}
