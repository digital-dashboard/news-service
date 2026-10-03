package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.source.SourceResolver;
import com.j11a.argus.testsupport.ScratchDatabase;
import com.j11a.argus.url.StoredUrls;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SeedIT extends AbstractIntegrationTest {

    private record ExpectedSource(String key, String name, String homepageUrl, String country) {}

    private record ExpectedFeed(String sourceKey, String name, String url, String topic) {}

    private static final List<ExpectedSource> EXPECTED_SOURCES = List.of(
            new ExpectedSource("bnnbloomberg.ca", "BNN Bloomberg", "https://www.bnnbloomberg.ca/", "CA"),
            new ExpectedSource("citynews.ca", "CityNews", "https://toronto.citynews.ca/", "CA"),
            new ExpectedSource("theglobeandmail.com", "The Globe and Mail", "https://www.theglobeandmail.com/", "CA"),
            new ExpectedSource("cbc.ca", "CBC", "https://www.cbc.ca/", "CA"),
            new ExpectedSource("globalnews.ca", "Global News", "https://globalnews.ca/", "CA"),
            new ExpectedSource("sportsnet.ca", "Sportsnet", "https://www.sportsnet.ca/", "CA"),
            new ExpectedSource("bbc.co.uk", "BBC", "https://www.bbc.co.uk/", "GB"),
            new ExpectedSource("dailymail.co.uk", "Daily Mail", "https://www.dailymail.co.uk/", "GB"),
            new ExpectedSource("mirror.co.uk", "The Mirror", "https://www.mirror.co.uk/", "GB"),
            new ExpectedSource("thesun.co.uk", "The Sun", "https://www.thesun.co.uk/", "GB"),
            new ExpectedSource("lusakatimes.com", "Lusaka Times", "https://www.lusakatimes.com/", "ZM"),
            new ExpectedSource("zambia24.com", "Zambia24", "https://zambia24.com/", "ZM"),
            new ExpectedSource("mwebantu.com", "Mwebantu", "https://www.mwebantu.com/", "ZM"),
            new ExpectedSource("zambianbusinesstimes.com", "Zambian Business Times", "https://zambianbusinesstimes.com/", "ZM"),
            new ExpectedSource("zambianfootball.co.zm", "Zambian Football", "https://www.zambianfootball.co.zm/", "ZM"),
            new ExpectedSource("lusakastar.com", "Lusaka Star", "https://www.lusakastar.com/", "ZM"),
            new ExpectedSource("farmersreviewafrica.com", "Farmers Review Africa", "https://www.farmersreviewafrica.com/", "ZM")
    );

    private static final List<ExpectedFeed> EXPECTED_FEEDS = List.of(
            new ExpectedFeed("bnnbloomberg.ca", "BNN Bloomberg", "https://www.bnnbloomberg.ca/arc/outboundfeeds/rss/?outputType=xml", "BUSINESS"),
            new ExpectedFeed("citynews.ca", "CityNews Toronto", "https://toronto.citynews.ca/feed/", "NEWS"),
            new ExpectedFeed("theglobeandmail.com", "The Globe and Mail (Canada)", "https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/", "NEWS"),
            new ExpectedFeed("cbc.ca", "CBC Top Stories", "https://www.cbc.ca/webfeed/rss/rss-topstories", "NEWS"),
            new ExpectedFeed("cbc.ca", "CBC Sports", "https://www.cbc.ca/webfeed/rss/rss-sports", "SPORT"),
            new ExpectedFeed("globalnews.ca", "Global News", "https://globalnews.ca/feed/", "NEWS"),
            new ExpectedFeed("sportsnet.ca", "Sportsnet", "https://www.sportsnet.ca/feed/", "SPORT"),
            new ExpectedFeed("bbc.co.uk", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "NEWS"),
            new ExpectedFeed("bbc.co.uk", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "WORLD"),
            new ExpectedFeed("bbc.co.uk", "BBC Football", "https://feeds.bbci.co.uk/sport/football/rss.xml", "SPORT"),
            new ExpectedFeed("dailymail.co.uk", "Daily Mail News", "https://www.dailymail.co.uk/news/index.rss", "NEWS"),
            new ExpectedFeed("mirror.co.uk", "The Mirror News", "https://www.mirror.co.uk/news/?service=rss", "NEWS"),
            new ExpectedFeed("thesun.co.uk", "The Sun", "https://www.thesun.co.uk/feed/", "NEWS"),
            new ExpectedFeed("lusakatimes.com", "Lusaka Times", "https://www.lusakatimes.com/feed/", "NEWS"),
            new ExpectedFeed("zambia24.com", "Zambia24", "https://zambia24.com/feed/", "NEWS"),
            new ExpectedFeed("mwebantu.com", "Mwebantu", "https://www.mwebantu.com/feed/", "NEWS"),
            new ExpectedFeed("zambianbusinesstimes.com", "Zambian Business Times", "https://zambianbusinesstimes.com/feed/", "BUSINESS"),
            new ExpectedFeed("zambianfootball.co.zm", "Zambian Football", "https://www.zambianfootball.co.zm/feed/", "SPORT"),
            new ExpectedFeed("lusakastar.com", "Lusaka Star", "https://www.lusakastar.com/feed/", "NEWS"),
            new ExpectedFeed("farmersreviewafrica.com", "Farmers Review Africa", "https://www.farmersreviewafrica.com/feed/", "BUSINESS")
    );

    @SuppressWarnings("rawtypes") // ScratchDatabase accepts raw PostgreSQLContainer
    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void seededMigrationInsertsAllSourcesAndFeeds() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrate("");

            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                List<ExpectedSource> actualSources = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery("SELECT key, name, homepage_url, country FROM source ORDER BY key")) {
                    while (rs.next()) {
                        actualSources.add(new ExpectedSource(
                                rs.getString("key"),
                                rs.getString("name"),
                                rs.getString("homepage_url"),
                                rs.getString("country")
                        ));
                    }
                }
                assertThat(actualSources).containsExactlyInAnyOrderElementsOf(EXPECTED_SOURCES);

                List<ExpectedFeed> actualFeeds = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT s.key, f.name, f.url, f.topic, f.enabled FROM feed f JOIN source s ON s.id = f.source_id")) {
                    while (rs.next()) {
                        assertThat(rs.getBoolean("enabled")).isTrue();
                        actualFeeds.add(new ExpectedFeed(
                                rs.getString("key"),
                                rs.getString("name"),
                                rs.getString("url"),
                                rs.getString("topic")
                        ));
                    }
                }
                assertThat(actualFeeds).containsExactlyInAnyOrderElementsOf(EXPECTED_FEEDS);

                try (ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM feed f JOIN source s ON s.id = f.source_id WHERE s.key = 'bbc.co.uk'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
        }
    }

    @Test
    void deletedFeedStaysDeletedOnSubsequentMigration() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrate("");
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                int deleted = stmt.executeUpdate(
                        "DELETE FROM feed WHERE url = 'https://www.cbc.ca/webfeed/rss/rss-sports'");
                assertThat(deleted).isEqualTo(1);
            }

            db.migrate("");

            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM source")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(17);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM feed")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(19);
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM feed WHERE url = 'https://www.cbc.ca/webfeed/rss/rss-sports'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM databasechangelog WHERE id = '09-seed-sources-and-feeds'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void testContextSkipsSeedMigration() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrate("test");
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM source")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM feed")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void prodInteractionPreservesExistingFeedAndHandlesOwnerEdits() throws Exception {
        assertProdBbcDefaultsUpdated();
        assertProdOwnerEditedNamePreserved();
    }

    private void assertProdBbcDefaultsUpdated() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(8, "");
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        INSERT INTO source (key, name, country, created_at, updated_at)
                        VALUES ('bbc.co.uk', 'bbc.co.uk', NULL, now(), now())""");
                stmt.execute("""
                        INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
                        SELECT id, 'BBC News', 'https://feeds.bbci.co.uk/news/rss.xml', 'NEWS', true, now(), now()
                        FROM source WHERE key = 'bbc.co.uk'""");
            }

            db.migrate("");

            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM feed WHERE url = 'https://feeds.bbci.co.uk/news/rss.xml'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT name, country FROM source WHERE key = 'bbc.co.uk'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("BBC");
                    assertThat(rs.getString("country")).isEqualTo("GB");
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM feed f JOIN source s ON s.id = f.source_id WHERE s.key = 'bbc.co.uk'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
        }
    }

    private void assertProdOwnerEditedNamePreserved() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(8, "");
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        INSERT INTO source (key, name, country, created_at, updated_at)
                        VALUES ('bbc.co.uk', 'BBC Online', NULL, now(), now())""");
                stmt.execute("""
                        INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
                        SELECT id, 'BBC News', 'https://feeds.bbci.co.uk/news/rss.xml', 'NEWS', true, now(), now()
                        FROM source WHERE key = 'bbc.co.uk'""");
            }

            db.migrate("");

            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT name, country FROM source WHERE key = 'bbc.co.uk'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("BBC Online");
                    assertThat(rs.getString("country")).isEqualTo("GB");
                }
            }
        }
    }

    @Test
    void seededFeedUrlsAreInStoredCleanForm() {
        for (ExpectedFeed feed : EXPECTED_FEEDS) {
            assertThat(StoredUrls.clean(feed.url()))
                    .as("Feed %s should already be in cleaned stored form", feed.name())
                    .isEqualTo(feed.url());
        }
    }

    @Test
    void seededFeedUrlsMatchSourceResolverKeysExceptBbc() {
        Map<String, String> bbcExceptions = Map.of(
                "https://feeds.bbci.co.uk/news/rss.xml", "bbc.co.uk",
                "https://feeds.bbci.co.uk/news/world/rss.xml", "bbc.co.uk",
                "https://feeds.bbci.co.uk/sport/football/rss.xml", "bbc.co.uk");

        for (ExpectedFeed feed : EXPECTED_FEEDS) {
            if (bbcExceptions.containsKey(feed.url())) {
                assertThat(feed.sourceKey()).isEqualTo(bbcExceptions.get(feed.url()));
                assertThat(SourceResolver.keyFor(null, URI.create(feed.url()))).isEqualTo("bbci.co.uk");
            } else {
                assertThat(SourceResolver.keyFor(null, URI.create(feed.url()))).isEqualTo(feed.sourceKey());
            }
        }
    }
}
