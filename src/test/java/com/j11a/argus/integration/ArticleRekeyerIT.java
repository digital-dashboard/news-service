package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.ingest.ContentHash;
import com.j11a.argus.migration.ArticleRekeyer;
import com.j11a.argus.migration.RekeyArticlesChange;
import com.j11a.argus.migration.RekeyReport;
import com.j11a.argus.testsupport.ScratchDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ArticleRekeyerIT extends AbstractIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void chainCaseResolvesWithoutTransientUniqueViolation() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(7, "test");

            try (Connection conn = db.connect()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        INSERT INTO source (id, key, name, homepage_url, created_at, updated_at)
                        VALUES (1, 'example.com', 'Example', 'https://example.com', now(), now());

                        INSERT INTO feed (id, source_id, name, url, topic, enabled, created_at, updated_at)
                        VALUES (1, 1, 'Main', 'https://example.com/feed', 'NEWS', true, now(), now());

                        -- Article 1: old guid_key is 'b', new guid_key is 'c'
                        INSERT INTO article (id, source_id, guid_key, raw_guid, link_key, link, title, excerpt, categories, effective_at, fetched_at, modified_at)
                        VALUES (1, 1, 'https://example.com/b', 'http://www.example.com/c', 'https://example.com/b', 'https://example.com/c', 'Article 1', 'Excerpt 1', ARRAY['news']::text[], now(), now(), now());

                        -- Article 2: old guid_key is 'a', new guid_key is 'b' (equals Article 1's old key)
                        INSERT INTO article (id, source_id, guid_key, raw_guid, link_key, link, title, excerpt, categories, effective_at, fetched_at, modified_at)
                        VALUES (2, 1, 'https://example.com/a', 'http://www.example.com/b', 'https://example.com/a', 'https://example.com/b', 'Article 2', 'Excerpt 2', ARRAY['tech']::text[], now(), now(), now());

                        INSERT INTO article_feed (article_id, feed_id, first_seen_at)
                        VALUES (1, 1, now()), (2, 1, now());
                        """);
                }

                RekeyReport report = new ArticleRekeyer().rekey(conn);

                assertThat(report.seen()).isEqualTo(2);
                assertThat(report.rekeyed()).isEqualTo(2);
                assertThat(report.collapsed()).isZero();
                assertThat(report.linksFolded()).isZero();

                String expectedHash1 = ContentHash.of("Article 1", "Excerpt 1", List.of("news"));
                String expectedHash2 = ContentHash.of("Article 2", "Excerpt 2", List.of("tech"));

                try (PreparedStatement ps = conn.prepareStatement("SELECT guid_key, link_key, content_hash FROM article WHERE id = ?")) {
                    ps.setLong(1, 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("guid_key")).isEqualTo("https://example.com/c");
                        assertThat(rs.getString("link_key")).isEqualTo("https://example.com/c");
                        assertThat(rs.getString("content_hash")).isEqualTo(expectedHash1);
                    }

                    ps.setLong(1, 2);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("guid_key")).isEqualTo("https://example.com/b");
                        assertThat(rs.getString("link_key")).isEqualTo("https://example.com/b");
                        assertThat(rs.getString("content_hash")).isEqualTo(expectedHash2);
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement("SELECT content_hash FROM article_feed WHERE article_id = ?")) {
                    ps.setLong(1, 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("content_hash")).isEqualTo(expectedHash1);
                    }

                    ps.setLong(1, 2);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("content_hash")).isEqualTo(expectedHash2);
                    }
                }
            }
        }
    }

    @Test
    void handlesNullGuidAndLinkByRetainingOldKey() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(7, "test");

            try (Connection conn = db.connect()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        INSERT INTO source (id, key, name, homepage_url, created_at, updated_at)
                        VALUES (1, 'example.com', 'Example', 'https://example.com', now(), now());

                        INSERT INTO feed (id, source_id, name, url, topic, enabled, created_at, updated_at)
                        VALUES (1, 1, 'Main', 'https://example.com/feed', 'NEWS', true, now(), now());

                        -- Article with null raw_guid and null link keeps existing guid_key
                        INSERT INTO article (id, source_id, guid_key, raw_guid, link_key, link, title, excerpt, categories, effective_at, fetched_at, modified_at)
                        VALUES (1, 1, 'urn:manual:1', NULL, NULL, NULL, 'Manual Article', NULL, ARRAY[]::text[], now(), now(), now());

                        INSERT INTO article_feed (article_id, feed_id, first_seen_at)
                        VALUES (1, 1, now());
                        """);
                }

                RekeyReport report = new ArticleRekeyer().rekey(conn);

                assertThat(report.seen()).isEqualTo(1);
                assertThat(report.rekeyed()).isZero();
                assertThat(report.collapsed()).isZero();
                assertThat(report.linksFolded()).isZero();

                try (PreparedStatement ps = conn.prepareStatement("SELECT guid_key, link_key, content_hash FROM article WHERE id = 1");
                     ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("guid_key")).isEqualTo("urn:manual:1");
                    assertThat(rs.getString("link_key")).isNull();
                    assertThat(rs.getString("content_hash")).isEqualTo(ContentHash.of("Manual Article", null, List.of()));
                }
            }
        }
    }

    @Test
    void rekeyEmptyTableReturnsZeroReport() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(7, "test");

            try (Connection conn = db.connect()) {
                RekeyReport report = new ArticleRekeyer().rekey(conn);

                assertThat(report.seen()).isZero();
                assertThat(report.rekeyed()).isZero();
                assertThat(report.collapsed()).isZero();
                assertThat(report.linksFolded()).isZero();
            }
        }
    }

    @Test
    void rekeyArticlesChangeLifecycleAndErrorHandling() throws Exception {
        RekeyArticlesChange change = new RekeyArticlesChange();
        change.setUp();
        change.setFileOpener(null);
        assertThat(change.validate(null).hasErrors()).isFalse();
        assertThat(change.getConfirmationMessage()).isEqualTo("Re-keyed articles: seen=0, rekeyed=0, collapsed=0, linksFolded=0");

        Database mockDb = mock(Database.class);
        JdbcConnection mockJdbc = mock(JdbcConnection.class);
        Connection mockConn = mock(Connection.class);

        when(mockDb.getConnection()).thenReturn(mockJdbc);
        when(mockJdbc.getUnderlyingConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("connection closed"));

        assertThatThrownBy(() -> change.execute(mockDb))
                .isInstanceOf(CustomChangeException.class)
                .hasCauseInstanceOf(SQLException.class);
    }
}
