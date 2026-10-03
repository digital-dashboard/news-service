package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.Fixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

class FeedApiIT extends AbstractIntegrationTest {

    private static final String FEEDS = "/news/v2/feeds";
    private static final String ARTICLES = "/news/v2/articles";
    private static final String RSS = "application/rss+xml";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private ResultActions createFeed(String path, String topic) throws Exception {
        return mockMvc.perform(post(FEEDS).header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + stub.baseUrl() + path + "\",\"topic\":\"" + topic + "\"}"));
    }

    private static String rss(String site, String... itemsNewestFirst) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title>")
                .append("<link>").append(site).append("</link><description>d</description>");
        for (String item : itemsNewestFirst) {
            xml.append(item);
        }
        return xml.append("</channel></rss>").toString();
    }

    private static String item(String slug, String date) {
        return "<item><title>" + slug + "</title><link>https://dated.example.test/" + slug + "</link><guid>" + slug
                + "</guid><pubDate>" + date + "</pubDate></item>";
    }

    @Test
    void postWithTheKeyCreatesTheFeedAndItCanBeFetched() throws Exception {
        stub.serveFixture("/api/ok.xml", "bbc-like-rss2.xml");

        String body = createFeed("/api/ok.xml", "WORLD")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/news/v2/feeds/\\d+")))
                .andExpect(jsonPath("$.name").value("Harbour Times - World"))
                .andExpect(jsonPath("$.topic").value("WORLD"))
                .andExpect(jsonPath("$.source.name").value("news.example.test"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("id").asLong();

        mockMvc.perform(get(FEEDS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void theSameUrlAgainIs409WithTheExistingFeedId() throws Exception {
        stub.serveFixture("/api/dup.xml", "bbc-like-rss2.xml");
        String body = createFeed("/api/dup.xml", "WORLD").andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("id").asLong();

        createFeed("/api/dup.xml", "WORLD")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEED_URL_CONFLICT"))
                .andExpect(jsonPath("$.existingFeedId").value(id));
    }

    @Test
    void aPageThatIsNotAFeedIs422WithTheReason() throws Exception {
        stub.serve("/api/page.html", 200, "text/html", Fixtures.feed("not-a-feed.html"));

        createFeed("/api/page.html", "TECH")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FEED_INVALID"))
                .andExpect(jsonPath("$.reason").value("not_a_feed"));

        assertThat(jdbcClient.sql("SELECT count(*) FROM feed").query(Long.class).single()).isZero();
    }

    @Test
    void anUnreachableStatusIs422WithoutLeakingUpstreamText() throws Exception {
        stub.serve("/api/gone.xml", 404, "text/plain", "secret upstream words".getBytes(StandardCharsets.UTF_8));

        String body = createFeed("/api/gone.xml", "TECH")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("http_status"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret upstream words");
    }

    @Test
    void invalidBodiesAreFieldErrors() throws Exception {
        mockMvc.perform(post(FEEDS).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"nope\",\"topic\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(FEEDS).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://x.test/a\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void postWithoutTheKeyIs401() throws Exception {
        mockMvc.perform(post(FEEDS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.test/rss\",\"topic\":\"TECH\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @Test
    void refreshOfAnUnknownFeedIs404() throws Exception {
        mockMvc.perform(post(FEEDS + "/99999/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void refreshReturnsTheReport() throws Exception {
        stub.serveFixture("/api/refresh.xml", "bbc-like-rss2.xml");
        long id = mapper.readTree(createFeed("/api/refresh.xml", "WORLD").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        mockMvc.perform(post(FEEDS + "/" + id + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.entriesSeen").value(2))
                .andExpect(jsonPath("$.unchanged").value(2));
    }

    @Test
    void articlesAreReadableWithoutAKeyNewestFirstInThePagedShape() throws Exception {
        stub.serve("/api/dated.xml", 200, RSS, rss("https://dated.example.test",
                item("oldest", "Mon, 01 Jan 2024 10:00:00 GMT"),
                item("newest", "Wed, 01 Jan 2025 10:00:00 GMT"),
                item("middle", "Tue, 01 Jul 2024 10:00:00 GMT")).getBytes(StandardCharsets.UTF_8));
        createFeed("/api/dated.xml", "NEWS").andExpect(status().isCreated());

        mockMvc.perform(get(ARTICLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].title").value("newest"))
                .andExpect(jsonPath("$.content[1].title").value("middle"))
                .andExpect(jsonPath("$.content[2].title").value("oldest"))
                .andExpect(jsonPath("$.content[0].publishedAt").value("2025-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].source.name").value("dated.example.test"))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void secondPageHoldsTheRemainder() throws Exception {
        stub.serve("/api/paged.xml", 200, RSS, rss("https://dated.example.test",
                item("a", "Mon, 01 Jan 2024 10:00:00 GMT"),
                item("b", "Tue, 02 Jan 2024 10:00:00 GMT"),
                item("c", "Wed, 03 Jan 2024 10:00:00 GMT")).getBytes(StandardCharsets.UTF_8));
        createFeed("/api/paged.xml", "NEWS");

        mockMvc.perform(get(ARTICLES).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("a"))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void oversizedPageIsRejectedNotClamped() throws Exception {
        mockMvc.perform(get(ARTICLES).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(ARTICLES).param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get(ARTICLES).param("size", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void twoFeedsOnTheSameSiteShareASource() throws Exception {
        stub.serveFixture("/api/world.xml", "bbc-like-rss2.xml");
        stub.serveFixture("/api/world-2.xml", "bbc-like-rss2.xml");

        createFeed("/api/world.xml", "WORLD").andExpect(status().isCreated());
        createFeed("/api/world-2.xml", "WORLD").andExpect(status().isCreated());

        assertThat(jdbcClient.sql("SELECT count(*) FROM source").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM feed").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article_feed").query(Long.class).single()).isEqualTo(4);
    }

    private static String feedWith(String title, String language) {
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>" + title + "</title>"
                + "<link>https://long.example.test/</link><description>d</description><language>" + language
                + "</language><item><title>a</title><link>https://long.example.test/a</link></item></channel></rss>";
    }

    @Test
    void aTitleAndLanguageLongerThanTheirColumnsStillCreateTheFeed() throws Exception {
        String xml = feedWith("T".repeat(300), "x".repeat(20));
        stub.serve("/api/long.xml", 200, RSS, xml.getBytes(StandardCharsets.UTF_8));

        String body = createFeed("/api/long.xml", "NEWS")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).path("name").asString()).hasSize(255);
        assertThat(jdbcClient.sql("SELECT count(*) FROM feed WHERE language IS NULL").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT length(name) FROM feed").query(Integer.class).single()).isEqualTo(255);
    }

    @Test
    void aUrlWithUserInfoIsAFieldError() throws Exception {
        mockMvc.perform(post(FEEDS).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://user:pass@example.test/feed\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void anOffsetBeyondTheIntRangeIs400NamingThePageNot500() throws Exception {
        mockMvc.perform(get(ARTICLES).param("page", "100000000").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void readsWithoutTheKeyHideTheFeedUrlQueryString() throws Exception {
        stub.serveFixture("/api/guarded.xml", "bbc-like-rss2.xml");
        long id = mapper.readTree(createFeed("/api/guarded.xml?token=abc", "WORLD").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        String body = mockMvc.perform(get(FEEDS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(stub.baseUrl() + "/api/guarded.xml"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("token");
    }

    @Test
    void readsWithTheKeyShowTheFullFeedUrl() throws Exception {
        stub.serveFixture("/api/guarded.xml", "bbc-like-rss2.xml");
        long id = mapper.readTree(createFeed("/api/guarded.xml?token=abc", "WORLD").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        mockMvc.perform(get(FEEDS + "/" + id).header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(stub.baseUrl() + "/api/guarded.xml?token=abc"));
    }

    @Test
    void createWithTheKeyEchoesTheFullFeedUrl() throws Exception {
        stub.serveFixture("/api/guarded.xml", "bbc-like-rss2.xml");

        createFeed("/api/guarded.xml?token=abc", "WORLD")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(stub.baseUrl() + "/api/guarded.xml?token=abc"));
    }

    @Test
    void listFeedsWithoutKeyRedactsUrlsAndIncludesHealth() throws Exception {
        stub.serveFixture("/api/list1.xml", "bbc-like-rss2.xml");
        createFeed("/api/list1.xml?token=secret", "WORLD").andExpect(status().isCreated());

        mockMvc.perform(get(FEEDS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].url").value(stub.baseUrl() + "/api/list1.xml"))
                .andExpect(jsonPath("$.content[0].state").value("healthy"))
                .andExpect(jsonPath("$.content[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listFeedsWithKeyShowsFullUrls() throws Exception {
        stub.serveFixture("/api/list2.xml", "bbc-like-rss2.xml");
        createFeed("/api/list2.xml?token=secret", "WORLD").andExpect(status().isCreated());

        mockMvc.perform(get(FEEDS).header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].url").value(stub.baseUrl() + "/api/list2.xml?token=secret"));
    }

    @Test
    void listFeedsInvalidPaginationReturnsBadRequest() throws Exception {
        mockMvc.perform(get(FEEDS).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(FEEDS).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(FEEDS).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(FEEDS).param("size", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFeedByIdIncludesHealthFields() throws Exception {
        stub.serveFixture("/api/health-check.xml", "bbc-like-rss2.xml");
        long id = mapper.readTree(createFeed("/api/health-check.xml", "WORLD").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        mockMvc.perform(get(FEEDS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("healthy"))
                .andExpect(jsonPath("$.consecutiveFailures").value(0))
                .andExpect(jsonPath("$.lastFetchedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty())
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void patchRequiresAdminKey() throws Exception {
        mockMvc.perform(patch(FEEDS + "/1").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @Test
    void patchInvalidBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(patch(FEEDS + "/1").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch(FEEDS + "/1").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void patchUnknownFeedReturnsNotFound() throws Exception {
        mockMvc.perform(patch(FEEDS + "/99999").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void patchUpdatesEnabledState() throws Exception {
        stub.serveFixture("/api/patch.xml", "bbc-like-rss2.xml");
        long id = mapper.readTree(createFeed("/api/patch.xml", "WORLD").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        mockMvc.perform(patch(FEEDS + "/" + id).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.state").value("disabled"));

        mockMvc.perform(patch(FEEDS + "/" + id).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.state").value("healthy"));
    }

    @Test
    void deleteRequiresAdminKey() throws Exception {
        mockMvc.perform(delete(FEEDS + "/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @Test
    void deleteUnknownFeedReturnsNotFound() throws Exception {
        mockMvc.perform(delete(FEEDS + "/99999").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void deleteRemovesOrphanArticlesOnlyAndPreservesSharedArticles() throws Exception {
        stub.serve("/api/del1.xml", 200, RSS, rss("https://del.example.test",
                item("shared", "Wed, 01 Jan 2025 10:00:00 GMT"),
                item("orphan-1", "Tue, 01 Jan 2024 10:00:00 GMT")).getBytes(StandardCharsets.UTF_8));
        stub.serve("/api/del2.xml", 200, RSS, rss("https://del.example.test",
                item("shared", "Wed, 01 Jan 2025 10:00:00 GMT"),
                item("other-2", "Mon, 01 Jan 2024 10:00:00 GMT")).getBytes(StandardCharsets.UTF_8));

        long feed1 = mapper.readTree(createFeed("/api/del1.xml", "NEWS").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();
        long feed2 = mapper.readTree(createFeed("/api/del2.xml", "NEWS").andReturn().getResponse()
                .getContentAsString()).path("id").asLong();

        assertThat(jdbcClient.sql("SELECT count(*) FROM feed").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isEqualTo(3);

        mockMvc.perform(delete(FEEDS + "/" + feed1).header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("SELECT count(*) FROM feed WHERE id = :id").param("id", feed1).query(Long.class).single())
                .isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM feed WHERE id = :id").param("id", feed2).query(Long.class).single())
                .isOne();

        List<String> remainingTitles = jdbcClient.sql("SELECT title FROM article ORDER BY title").query(String.class).list();
        assertThat(remainingTitles).containsExactly("other-2", "shared");
    }

    @Test
    void manualRefreshAllRequiresAdminKey() throws Exception {
        mockMvc.perform(post(FEEDS + "/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
    }

    @Test
    void manualRefreshAllExecutesAndReturnsAggregateReport() throws Exception {
        stub.serveFixture("/api/poll-all.xml", "bbc-like-rss2.xml");
        createFeed("/api/poll-all.xml", "WORLD").andExpect(status().isCreated());

        mockMvc.perform(post(FEEDS + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value("manual"))
                .andExpect(jsonPath("$.feedsPolled").value(1))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.entriesSeen").value(2))
                .andExpect(jsonPath("$.unchanged").value(2))
                .andExpect(jsonPath("$.reports.length()").value(1));
    }
}
