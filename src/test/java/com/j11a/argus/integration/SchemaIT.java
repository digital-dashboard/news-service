package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.article.Article;
import com.j11a.argus.article.ArticleRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

class SchemaIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private ArticleRepository articles;

    private long insertSource(String key) {
        return jdbcClient.sql("""
                        INSERT INTO source (key, name, created_at, updated_at) VALUES (:key, :key, :now, :now)
                        RETURNING id""")
                .param("key", key).param("now", NOW).query(Long.class).single();
    }

    private long insertFeed(long sourceId, String url) {
        return jdbcClient.sql("""
                        INSERT INTO feed (source_id, name, url, topic, created_at, updated_at)
                        VALUES (:source, 'n', :url, 'TECH', :now, :now) RETURNING id""")
                .param("source", sourceId).param("url", url).param("now", NOW).query(Long.class).single();
    }

    private long insertArticle(long sourceId, String guidKey, String[] categories) {
        return jdbcClient.sql("""
                        INSERT INTO article (source_id, guid_key, title, categories, effective_at, fetched_at,
                                             modified_at)
                        VALUES (:source, :guid, 't', :categories, :now, :now, :now) RETURNING id""")
                .param("source", sourceId).param("guid", guidKey).param("categories", categories)
                .param("now", NOW).query(Long.class).single();
    }

    @Test
    void sourceKeyIsUnique() {
        insertSource("example.test");

        assertThatThrownBy(() -> insertSource("example.test"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_source_key");
    }

    @Test
    void feedUrlIsUnique() {
        long source = insertSource("example.test");
        insertFeed(source, "https://example.test/rss");

        assertThatThrownBy(() -> insertFeed(source, "https://example.test/rss"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_feed_url");
    }

    @Test
    void articleGuidKeyIsUniquePerSourceButNotAcrossSources() {
        long first = insertSource("a.test");
        long second = insertSource("b.test");
        insertArticle(first, "guid-1", new String[0]);

        insertArticle(second, "guid-1", new String[0]);

        assertThatThrownBy(() -> insertArticle(first, "guid-1", new String[0]))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_article_source_guid_key");
    }

    @Test
    void articleFeedRowsAreRemovedWhenTheFeedIsDeleted() {
        long source = insertSource("example.test");
        long feed = insertFeed(source, "https://example.test/rss");
        long article = insertArticle(source, "guid-1", new String[0]);
        jdbcClient.sql("INSERT INTO article_feed (article_id, feed_id, first_seen_at) VALUES (:a, :f, :now)")
                .param("a", article).param("f", feed).param("now", NOW).update();

        jdbcClient.sql("DELETE FROM feed WHERE id = :id").param("id", feed).update();

        assertThat(jdbcClient.sql("SELECT count(*) FROM article_feed").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isOne();
    }

    @Test
    void categoriesRoundTripThroughTheEntity() {
        long source = insertSource("example.test");
        insertArticle(source, "guid-1", new String[] {"World", "Match report"});
        insertArticle(source, "guid-2", new String[0]);

        var page = articles.findPage(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Article::getCategories)
                .containsExactlyInAnyOrder(java.util.List.of("World", "Match report"), java.util.List.of());
        assertThat(page.getContent()).allSatisfy(article ->
                assertThat(article.getSource().getKey()).isEqualTo("example.test"));
    }

    @Test
    void timestampsAreStoredWithATimeZone() {
        String type = jdbcClient.sql("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_name = 'article' AND column_name = 'effective_at'""")
                .query(String.class).single();

        assertThat(type).isEqualTo("timestamp with time zone");
    }

    @Test
    void feedHealthColumnsAndIndexExist() {
        long source = insertSource("example.test");
        long feed = insertFeed(source, "https://example.test/rss");

        var row = jdbcClient.sql("""
                        SELECT etag, last_modified, last_fetched_at, last_success_at, last_error, consecutive_failures
                        FROM feed WHERE id = :id""")
                .param("id", feed)
                .query()
                .singleRow();

        assertThat(row).containsEntry("consecutive_failures", 0);
        assertThat(row.get("etag")).isNull();
        assertThat(row.get("last_modified")).isNull();

        long indexCount = jdbcClient.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE tablename = 'feed' AND indexname = 'ix_feed_enabled'""")
                .query(Long.class).single();
        assertThat(indexCount).isOne();
    }

    @Test
    void contentHashColumnsExistAndAreNullable() {
        String articleNullable = jdbcClient.sql("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_name = 'article' AND column_name = 'content_hash'""")
                .query(String.class).single();
        assertThat(articleNullable).isEqualTo("YES");

        String articleFeedNullable = jdbcClient.sql("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_name = 'article_feed' AND column_name = 'content_hash'""")
                .query(String.class).single();
        assertThat(articleFeedNullable).isEqualTo("YES");
    }
}
