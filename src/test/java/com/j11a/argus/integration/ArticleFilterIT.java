package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.feed.Topic;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArticleFilterIT extends AbstractIntegrationTest {

    private static final String ARTICLES = "/news/v2/articles";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUpStatistics() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    private long insertSource(String key, String name, String country) {
        return jdbcClient.sql("""
                        INSERT INTO source (key, name, country, created_at, updated_at)
                        VALUES (:key, :name, :country, :now, :now)
                        RETURNING id""")
                .param("key", key)
                .param("name", name)
                .param("country", country)
                .param("now", NOW)
                .query(Long.class)
                .single();
    }

    private long insertFeed(long sourceId, String name, String url, Topic topic) {
        return jdbcClient.sql("""
                        INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
                        VALUES (:sourceId, :name, :url, :topic, true, :now, :now)
                        RETURNING id""")
                .param("sourceId", sourceId)
                .param("name", name)
                .param("url", url)
                .param("topic", topic.name())
                .param("now", NOW)
                .query(Long.class)
                .single();
    }

    private long insertArticle(long sourceId, String guidKey, String title, Instant effectiveAt) {
        return jdbcClient.sql("""
                        INSERT INTO article (source_id, guid_key, title, categories, effective_at, fetched_at, modified_at)
                        VALUES (:sourceId, :guidKey, :title, :categories, :effectiveAt, :now, :now)
                        RETURNING id""")
                .param("sourceId", sourceId)
                .param("guidKey", guidKey)
                .param("title", title)
                .param("categories", new String[0])
                .param("effectiveAt", effectiveAt.atOffset(ZoneOffset.UTC))
                .param("now", NOW)
                .query(Long.class)
                .single();
    }

    private void linkArticleFeed(long articleId, long feedId) {
        jdbcClient.sql("""
                        INSERT INTO article_feed (article_id, feed_id, first_seen_at)
                        VALUES (:articleId, :feedId, :now)""")
                .param("articleId", articleId)
                .param("feedId", feedId)
                .param("now", NOW)
                .update();
    }

    @Test
    void articleLinkedToMultipleFeedsAppearsOnceWithoutDuplicates() throws Exception {
        long source = insertSource("src1", "Source One", "CA");
        long newsFeed = insertFeed(source, "News Feed", "https://src1.test/news.xml", Topic.NEWS);
        long sportFeed = insertFeed(source, "Sport Feed", "https://src1.test/sport.xml", Topic.SPORT);

        long dualArticle = insertArticle(source, "g1", "Dual Article", Instant.parse("2026-10-02T12:00:00Z"));
        linkArticleFeed(dualArticle, newsFeed);
        linkArticleFeed(dualArticle, sportFeed);

        long newsOnly = insertArticle(source, "g2", "News Only", Instant.parse("2026-10-02T11:00:00Z"));
        linkArticleFeed(newsOnly, newsFeed);

        long sportOnly = insertArticle(source, "g3", "Sport Only", Instant.parse("2026-10-02T10:00:00Z"));
        linkArticleFeed(sportOnly, sportFeed);

        // topic=NEWS
        mockMvc.perform(get(ARTICLES).param("topic", "NEWS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Dual Article"))
                .andExpect(jsonPath("$.content[1].title").value("News Only"));

        // topic=SPORT
        mockMvc.perform(get(ARTICLES).param("topic", "SPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Dual Article"))
                .andExpect(jsonPath("$.content[1].title").value("Sport Only"));

        // topic=NEWS & topic=SPORT (dual article appears once)
        mockMvc.perform(get(ARTICLES).param("topic", "NEWS").param("topic", "SPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].title").value("Dual Article"))
                .andExpect(jsonPath("$.content[1].title").value("News Only"))
                .andExpect(jsonPath("$.content[2].title").value("Sport Only"));
    }

    @Test
    void countryFilterUsesSource() throws Exception {
        long caSource = insertSource("ca-src", "CA Source", "CA");
        long gbSource = insertSource("gb-src", "GB Source", "GB");
        long feedCa = insertFeed(caSource, "CA Feed", "https://ca.test/feed.xml", Topic.NEWS);
        long feedGb = insertFeed(gbSource, "GB Feed", "https://gb.test/feed.xml", Topic.NEWS);

        long caArticle = insertArticle(caSource, "ca1", "CA Article", Instant.parse("2026-10-02T12:00:00Z"));
        linkArticleFeed(caArticle, feedCa);

        long gbArticle = insertArticle(gbSource, "gb1", "GB Article", Instant.parse("2026-10-02T11:00:00Z"));
        linkArticleFeed(gbArticle, feedGb);

        mockMvc.perform(get(ARTICLES).param("country", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("CA Article"))
                .andExpect(jsonPath("$.content[0].source.country").value("CA"));

        mockMvc.perform(get(ARTICLES).param("country", "gb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("GB Article"))
                .andExpect(jsonPath("$.content[0].source.country").value("GB"));

        mockMvc.perform(get(ARTICLES).param("country", "CA").param("country", "GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void combinedTopicAndCountryFilter() throws Exception {
        long caSource = insertSource("ca-src", "CA Source", "CA");
        long gbSource = insertSource("gb-src", "GB Source", "GB");

        long feedCaNews = insertFeed(caSource, "CA News", "https://ca.test/news.xml", Topic.NEWS);
        long feedCaSport = insertFeed(caSource, "CA Sport", "https://ca.test/sport.xml", Topic.SPORT);
        long feedGbNews = insertFeed(gbSource, "GB News", "https://gb.test/news.xml", Topic.NEWS);
        long feedGbSport = insertFeed(gbSource, "GB Sport", "https://gb.test/sport.xml", Topic.SPORT);

        long caNewsArticle = insertArticle(caSource, "ca-n", "CA News Article", Instant.parse("2026-10-02T12:00:00Z"));
        linkArticleFeed(caNewsArticle, feedCaNews);

        long caSportArticle = insertArticle(caSource, "ca-s", "CA Sport Article", Instant.parse("2026-10-02T11:00:00Z"));
        linkArticleFeed(caSportArticle, feedCaSport);

        long gbNewsArticle = insertArticle(gbSource, "gb-n", "GB News Article", Instant.parse("2026-10-02T10:00:00Z"));
        linkArticleFeed(gbNewsArticle, feedGbNews);

        long gbSportArticle = insertArticle(gbSource, "gb-s", "GB Sport Article", Instant.parse("2026-10-02T09:00:00Z"));
        linkArticleFeed(gbSportArticle, feedGbSport);

        mockMvc.perform(get(ARTICLES).param("topic", "NEWS").param("country", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("CA News Article"));
    }

    @Test
    void feedsAreListedInResponseSortedById() throws Exception {
        long source = insertSource("src", "Source", "CA");
        long feed1 = insertFeed(source, "Feed Beta", "https://src.test/beta.xml", Topic.NEWS);
        long feed2 = insertFeed(source, "Feed Alpha", "https://src.test/alpha.xml", Topic.SPORT);

        long article = insertArticle(source, "a1", "Article", Instant.parse("2026-10-02T12:00:00Z"));
        linkArticleFeed(article, feed2);
        linkArticleFeed(article, feed1);

        long minFeedId = Math.min(feed1, feed2);
        long maxFeedId = Math.max(feed1, feed2);

        mockMvc.perform(get(ARTICLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].feeds.length()").value(2))
                .andExpect(jsonPath("$.content[0].feeds[0].id").value(minFeedId))
                .andExpect(jsonPath("$.content[0].feeds[1].id").value(maxFeedId));
    }

    @Test
    void pagingWithFiltersReturnsNoDuplicates() throws Exception {
        long source = insertSource("src", "Source", "CA");
        long feed1 = insertFeed(source, "News 1", "https://src.test/news1.xml", Topic.NEWS);
        long feed2 = insertFeed(source, "News 2", "https://src.test/news2.xml", Topic.NEWS);

        for (int i = 0; i < 5; i++) {
            long article = insertArticle(source, "g" + i, "Article " + i,
                    Instant.parse("2026-10-02T10:00:00Z").plusSeconds(i * 60));
            linkArticleFeed(article, feed1);
            linkArticleFeed(article, feed2);
        }

        mockMvc.perform(get(ARTICLES).param("topic", "NEWS").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].title").value("Article 4"))
                .andExpect(jsonPath("$.content[1].title").value("Article 3"));

        mockMvc.perform(get(ARTICLES).param("topic", "NEWS").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Article 2"))
                .andExpect(jsonPath("$.content[1].title").value("Article 1"));

        mockMvc.perform(get(ARTICLES).param("topic", "NEWS").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Article 0"));
    }

    @Test
    void oneBatchQueryForFeeds() throws Exception {
        long source = insertSource("src", "Source", "CA");
        long feed1 = insertFeed(source, "F1", "https://src.test/f1.xml", Topic.NEWS);
        long feed2 = insertFeed(source, "F2", "https://src.test/f2.xml", Topic.SPORT);

        for (int i = 0; i < 5; i++) {
            long article = insertArticle(source, "art" + i, "Article " + i,
                    Instant.parse("2026-10-02T10:00:00Z").plusSeconds(i * 60));
            linkArticleFeed(article, feed1);
            linkArticleFeed(article, feed2);
        }

        statistics.clear();

        mockMvc.perform(get(ARTICLES).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].feeds.length()").value(2))
                .andExpect(jsonPath("$.content[4].feeds.length()").value(2));

        assertThat(statistics.getCollectionStatistics("com.j11a.argus.article.Article.feeds").getFetchCount())
                .isEqualTo(1L);
    }
}
