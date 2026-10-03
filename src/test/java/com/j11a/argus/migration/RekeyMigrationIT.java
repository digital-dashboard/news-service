package com.j11a.argus.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.ContentHash;
import com.j11a.argus.ingest.dedup.DedupInput;
import com.j11a.argus.ingest.dedup.EntryDecision;
import com.j11a.argus.ingest.dedup.EntryDedupResolver;
import com.j11a.argus.ingest.dedup.ExistingArticle;
import com.j11a.argus.ingest.dedup.ExistingArticleLookup;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.integration.AbstractIntegrationTest;
import com.j11a.argus.testsupport.ScratchDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RekeyMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private record ArticleRow(long id, String guidKey, @Nullable String linkKey, String contentHash, Instant modifiedAt) {}
    private record FeedRow(long articleId, long feedId, Instant firstSeenAt, @Nullable String contentHash) {}

    @Test
    void migrationRekeyCollapsesDuplicatesBackfillsHashesAndIsIdempotent() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            // 1. migrateFirst(7, "test") applies 01–07
            db.migrateFirst(7, "test");

            Instant t1 = Instant.parse("2026-10-01T10:00:00Z");
            Instant t2 = Instant.parse("2026-10-01T12:00:00Z");
            Instant tEarly = Instant.parse("2026-10-01T08:00:00Z");

            // 2. Insert phase-2-shaped rows with interim keys
            try (Connection conn = db.connect()) {
                insertPhase2Articles(conn, t1, t2, tEarly);
            }

            // 3. migrate("test") applies the rest (including 08-rekey-articles)
            db.migrate("test");

            // 4. Assert migration outcomes, idempotence and no duplicates on first poll
            try (Connection conn = db.connect()) {
                assertMigratedArticles(conn, t1, tEarly);
                assertIdempotence(conn);
                assertFirstPollNoDuplicates(conn, t1);
            }
        }
    }

    private void insertPhase2Articles(Connection conn, Instant t1, Instant t2, Instant tEarly) throws SQLException {
        try (PreparedStatement insSrc = conn.prepareStatement(
                "INSERT INTO source (id, key, name, homepage_url, created_at, updated_at) VALUES (1, 'news.example.test', 'News Example', 'https://news.example.test', ?, ?)");
             PreparedStatement insFeed = conn.prepareStatement(
                "INSERT INTO feed (id, source_id, name, url, topic, enabled, created_at, updated_at) VALUES (?, 1, ?, ?, 'NEWS', true, ?, ?)");
             PreparedStatement insArt = conn.prepareStatement("""
                INSERT INTO article (id, source_id, guid_key, raw_guid, link_key, link, title, excerpt, categories,
                                     published_at, updated_at_upstream, effective_at, fetched_at, modified_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?)
                """);
             PreparedStatement insLink = conn.prepareStatement(
                "INSERT INTO article_feed (article_id, feed_id, first_seen_at) VALUES (?, ?, ?)")) {

            insSrc.setTimestamp(1, Timestamp.from(t1));
            insSrc.setTimestamp(2, Timestamp.from(t1));
            insSrc.executeUpdate();

            insertFeed(insFeed, 1L, "Feed 1", "https://news.example.test/feed1", t1);
            insertFeed(insFeed, 2L, "Feed 2", "https://news.example.test/feed2", t1);

            insertArticle(insArt, conn, 1L, "https://www.news.example.test/a/1", "https://www.news.example.test/a/1#0",
                    "https://www.news.example.test/a/1", "https://www.news.example.test/a/1?at_medium=rss",
                    "Article A", "Excerpt A", new String[]{"world"}, t1);
            insertArticle(insArt, conn, 2L, "http://news.example.test/a/1/", "http://news.example.test/a/1/",
                    "http://news.example.test/a/1/", "https://www.news.example.test/a/1?at_medium=rss",
                    "Article B", "Excerpt B", new String[]{"world"}, t1);
            insertArticle(insArt, conn, 3L, "urn:uuid:123", "urn:uuid:123",
                    "https://news.example.test/c", "https://news.example.test/c",
                    "Article C", "Excerpt C", new String[]{"culture"}, t1);
            insertArticle(insArt, conn, 4L, "urn:uuid:d-456", "urn:uuid:d-456",
                    "https://news.example.test/shared-link", "https://news.example.test/shared-link",
                    "Article D", "Excerpt D", new String[]{"science"}, t1);
            insertArticle(insArt, conn, 5L, "urn:uuid:e-789", "urn:uuid:e-789",
                    "https://news.example.test/shared-link", "https://news.example.test/shared-link",
                    "Article E", "Excerpt E", new String[]{"tech"}, t1);

            insertFeedLink(insLink, 1L, 1L, t2);
            insertFeedLink(insLink, 2L, 1L, tEarly);
            insertFeedLink(insLink, 2L, 2L, t1);
            insertFeedLink(insLink, 3L, 1L, t1);
            insertFeedLink(insLink, 4L, 1L, t1);
            insertFeedLink(insLink, 5L, 1L, t1);
        }
    }

    private void insertFeed(PreparedStatement ps, long id, String name, String url, Instant time) throws SQLException {
        ps.setLong(1, id);
        ps.setString(2, name);
        ps.setString(3, url);
        ps.setTimestamp(4, Timestamp.from(time));
        ps.setTimestamp(5, Timestamp.from(time));
        ps.executeUpdate();
    }

    private void insertArticle(PreparedStatement ps, Connection conn, long id, String guidKey, String rawGuid,
                               String linkKey, String link, String title, String excerpt, String[] categories,
                               Instant time) throws SQLException {
        ps.setLong(1, id);
        ps.setString(2, guidKey);
        ps.setString(3, rawGuid);
        ps.setString(4, linkKey);
        ps.setString(5, link);
        ps.setString(6, title);
        ps.setString(7, excerpt);
        ps.setArray(8, conn.createArrayOf("text", categories));
        ps.setTimestamp(9, Timestamp.from(time));
        ps.setTimestamp(10, Timestamp.from(time));
        ps.setTimestamp(11, Timestamp.from(time));
        ps.setTimestamp(12, Timestamp.from(time));
        ps.setTimestamp(13, Timestamp.from(time));
        ps.executeUpdate();
    }

    private void insertFeedLink(PreparedStatement ps, long articleId, long feedId, Instant time) throws SQLException {
        ps.setLong(1, articleId);
        ps.setLong(2, feedId);
        ps.setTimestamp(3, Timestamp.from(time));
        ps.executeUpdate();
    }

    private void assertMigratedArticles(Connection conn, Instant t1, Instant tEarly) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertQueryEquals(stmt, "SELECT count(*) FROM article WHERE id = 2", 0);
            assertQueryEquals(stmt, "SELECT count(*) FROM databasechangelog WHERE id = '08-rekey-articles'", 1);
            assertQueryEquals(stmt, "SELECT count(*) FROM article WHERE id IN (4, 5)", 2);
        }

        try (PreparedStatement ps = conn.prepareStatement("SELECT guid_key FROM article WHERE id = ?")) {
            ps.setLong(1, 1L);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("guid_key")).isEqualTo("https://news.example.test/a/1");
            }
            ps.setLong(1, 3L);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("guid_key")).isEqualTo("urn:uuid:123");
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT feed_id, first_seen_at FROM article_feed WHERE article_id = 1 ORDER BY feed_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("feed_id")).isEqualTo(1L);
                assertThat(rs.getTimestamp("first_seen_at").toInstant()).isEqualTo(tEarly);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("feed_id")).isEqualTo(2L);
                assertThat(rs.getTimestamp("first_seen_at").toInstant()).isEqualTo(t1);
                assertThat(rs.next()).isFalse();
            }
        }

        assertContentHashes(conn);
    }

    private void assertContentHashes(Connection conn) throws SQLException {
        String hashA = ContentHash.of("Article A", "Excerpt A", List.of("world"));
        String hashC = ContentHash.of("Article C", "Excerpt C", List.of("culture"));
        String hashD = ContentHash.of("Article D", "Excerpt D", List.of("science"));
        String hashE = ContentHash.of("Article E", "Excerpt E", List.of("tech"));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, content_hash FROM article ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("content_hash")).isEqualTo(hashA);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("content_hash")).isEqualTo(hashC);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("content_hash")).isEqualTo(hashD);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("content_hash")).isEqualTo(hashE);
            assertThat(rs.next()).isFalse();
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT article_id, content_hash FROM article_feed ORDER BY article_id, feed_id")) {
            while (rs.next()) {
                long artId = rs.getLong("article_id");
                String hash = rs.getString("content_hash");
                assertThat(hash).isNotNull();
                String expected = artId == 1L ? hashA : (artId == 3L ? hashC : (artId == 4L ? hashD : hashE));
                assertThat(hash).isEqualTo(expected);
            }
        }
    }

    private void assertIdempotence(Connection conn) throws SQLException {
        List<ArticleRow> articlesBefore = queryArticles(conn);
        List<FeedRow> feedsBefore = queryFeeds(conn);

        RekeyReport report = new ArticleRekeyer().rekey(conn);

        assertThat(report.seen()).isEqualTo(4);
        assertThat(report.rekeyed()).isZero();
        assertThat(report.collapsed()).isZero();
        assertThat(report.linksFolded()).isZero();

        List<ArticleRow> articlesAfter = queryArticles(conn);
        List<FeedRow> feedsAfter = queryFeeds(conn);
        assertThat(articlesAfter).isEqualTo(articlesBefore);
        assertThat(feedsAfter).isEqualTo(feedsBefore);
    }

    private void assertFirstPollNoDuplicates(Connection conn, Instant t1) {
        ExistingArticleLookup lookup = createLookup(conn, 1L, 1L);
        ParsedEntry entryA = new ParsedEntry("https://www.news.example.test/a/1#0",
                "https://www.news.example.test/a/1?at_medium=rss", "Article A", "Excerpt A", null, null, List.of("world"), t1, t1);
        ParsedEntry entryC = new ParsedEntry("urn:uuid:123", "https://news.example.test/c",
                "Article C", "Excerpt C", null, null, List.of("culture"), t1, t1);
        ParsedEntry entryD = new ParsedEntry("urn:uuid:d-456", "https://news.example.test/shared-link",
                "Article D", "Excerpt D", null, null, List.of("science"), t1, t1);
        ParsedEntry entryE = new ParsedEntry("urn:uuid:e-789", "https://news.example.test/shared-link",
                "Article E", "Excerpt E", null, null, List.of("tech"), t1, t1);

        DedupInput input = new DedupInput(1L, List.of(entryA, entryC, entryD, entryE), "https://news.example.test", t1, lookup);
        Resolution resolution = new EntryDedupResolver().resolve(input);

        assertThat(resolution.decisions())
                .noneMatch(d -> d instanceof EntryDecision.Insert)
                .noneMatch(d -> d instanceof EntryDecision.Update);
    }

    private void assertQueryEquals(Statement stmt, String sql, int expected) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(expected);
        }
    }

    private List<ArticleRow> queryArticles(Connection conn) throws SQLException {
        List<ArticleRow> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, guid_key, link_key, content_hash, modified_at FROM article ORDER BY id")) {
            while (rs.next()) {
                list.add(new ArticleRow(
                        rs.getLong("id"),
                        rs.getString("guid_key"),
                        rs.getString("link_key"),
                        rs.getString("content_hash"),
                        rs.getTimestamp("modified_at").toInstant()));
            }
        }
        return list;
    }

    private List<FeedRow> queryFeeds(Connection conn) throws SQLException {
        List<FeedRow> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT article_id, feed_id, first_seen_at, content_hash FROM article_feed ORDER BY article_id, feed_id")) {
            while (rs.next()) {
                list.add(new FeedRow(
                        rs.getLong("article_id"),
                        rs.getLong("feed_id"),
                        rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getString("content_hash")));
            }
        }
        return list;
    }

    private ExistingArticleLookup createLookup(Connection conn, long sourceId, long feedId) {
        return (guidKeys, linkKeys) -> {
            String sql = """
                    SELECT a.id, a.guid_key, a.link_key, a.updated_at_upstream,
                           af.article_id IS NOT NULL AS linked, af.content_hash AS feed_content_hash
                    FROM article a
                    LEFT JOIN article_feed af ON af.article_id = a.id AND af.feed_id = ?
                    WHERE a.source_id = ?
                      AND (a.guid_key = ANY(CAST(? AS text[])) OR a.link_key = ANY(CAST(? AS text[])))
                    """;
            List<ExistingArticle> articles = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, feedId);
                ps.setLong(2, sourceId);
                ps.setArray(3, conn.createArrayOf("text", guidKeys.toArray(String[]::new)));
                ps.setArray(4, conn.createArrayOf("text", linkKeys.toArray(String[]::new)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String guidKey = rs.getString("guid_key");
                        String linkKey = rs.getString("link_key");
                        Timestamp ts = rs.getTimestamp("updated_at_upstream");
                        Instant updatedAtUpstream = ts != null ? ts.toInstant() : null;
                        boolean linked = rs.getBoolean("linked");
                        String feedContentHash = rs.getString("feed_content_hash");
                        articles.add(new ExistingArticle(id, guidKey, linkKey, updatedAtUpstream, linked, feedContentHash));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return articles;
        };
    }
}
