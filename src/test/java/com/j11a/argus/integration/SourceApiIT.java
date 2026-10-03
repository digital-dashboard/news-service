package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.testsupport.AdminKeys;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

class SourceApiIT extends AbstractIntegrationTest {

    private static final String SOURCES = "/news/v2/sources";
    private static final String FEEDS = "/news/v2/feeds";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private ResultActions adminPatch(String path, String body) throws Exception {
        return mockMvc.perform(patch(path)
                .header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions adminPost(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String rss(String site, String... items) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title>")
                .append("<link>").append(site).append("</link><description>d</description>");
        for (String item : items) {
            xml.append(item);
        }
        return xml.append("</channel></rss>").toString();
    }

    private static String item(String slug) {
        return "<item><title>" + slug + "</title><link>https://example.test/" + slug + "</link><guid>" + slug
                + "</guid><pubDate>Tue, 06 Oct 2026 07:30:00 GMT</pubDate></item>";
    }

    @Test
    void twoFeedsOfOneSourceShowInFeedsWithArticleCountCorrect() throws Exception {
        stub.serveFixture("/source-feed1.xml", "bbc-like-rss2.xml");
        FeedResponse f1 = feedService.create(new CreateFeedRequest(
                stub.baseUrl() + "/source-feed1.xml", "Feed 1", Topic.WORLD, null));
        long sourceId = f1.source().id();

        String feed2Xml = rss("https://news.example.test/world", item("extra-article-1"), item("extra-article-2"));
        stub.serve("/source-feed2.xml", 200, "application/rss+xml", feed2Xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FeedResponse f2 = feedService.create(new CreateFeedRequest(
                stub.baseUrl() + "/source-feed2.xml", "Feed 2", Topic.NEWS, null));

        assertThat(f2.source().id()).isEqualTo(sourceId);

        // bbc-like-rss2 has 2 articles, feed2 has 2 articles => total 4 articles for this source
        mockMvc.perform(get(SOURCES + "/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sourceId))
                .andExpect(jsonPath("$.key").value("news.example.test"))
                .andExpect(jsonPath("$.articleCount").value(4))
                .andExpect(jsonPath("$.feeds").isArray())
                .andExpect(jsonPath("$.feeds[0].id").value(f1.id()))
                .andExpect(jsonPath("$.feeds[0].name").value("Feed 1"))
                .andExpect(jsonPath("$.feeds[0].topic").value("WORLD"))
                .andExpect(jsonPath("$.feeds[1].id").value(f2.id()))
                .andExpect(jsonPath("$.feeds[1].name").value("Feed 2"))
                .andExpect(jsonPath("$.feeds[1].topic").value("NEWS"));

        mockMvc.perform(get(SOURCES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(sourceId))
                .andExpect(jsonPath("$.content[0].articleCount").value(4))
                .andExpect(jsonPath("$.content[0].feeds.length()").value(2));
    }

    @Test
    void patchNameHomepageAndCountryPersist() throws Exception {
        stub.serveFixture("/patch-source.xml", "bbc-like-rss2.xml");
        FeedResponse f = feedService.create(new CreateFeedRequest(
                stub.baseUrl() + "/patch-source.xml", null, Topic.WORLD, null));
        long sourceId = f.source().id();

        adminPatch(SOURCES + "/" + sourceId, "{\"name\":\"Updated Source\",\"homepage\":\"https://updated.example.test/home\",\"country\":\"ca\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sourceId))
                .andExpect(jsonPath("$.name").value("Updated Source"))
                .andExpect(jsonPath("$.homepage").value("https://updated.example.test/home"))
                .andExpect(jsonPath("$.country").value("CA"));

        mockMvc.perform(get(SOURCES + "/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Source"))
                .andExpect(jsonPath("$.homepage").value("https://updated.example.test/home"))
                .andExpect(jsonPath("$.country").value("CA"));
    }

    @Test
    void postFeedsWithSourceIdAttachesFeedToThatSourceEvenThoughFixturePointsElsewhere() throws Exception {
        stub.serveFixture("/initial.xml", "bbc-like-rss2.xml");
        FeedResponse initial = feedService.create(new CreateFeedRequest(
                stub.baseUrl() + "/initial.xml", null, Topic.WORLD, null));
        long sourceId = initial.source().id();

        // Feed fixture has link pointing to different-site.test
        String otherSiteXml = rss("https://different-site.test/home", item("diff-1"));
        stub.serve("/explicit-source.xml", 200, "application/rss+xml", otherSiteXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String createPayload = """
                {
                    "url": "%s/explicit-source.xml",
                    "name": "Explicit Source Feed",
                    "topic": "TECH",
                    "sourceId": %d
                }
                """.formatted(stub.baseUrl(), sourceId);

        adminPost(FEEDS, createPayload)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.id").value(sourceId))
                .andExpect(jsonPath("$.source.name").value(initial.source().name()));

        mockMvc.perform(get(SOURCES + "/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeds.length()").value(2));
    }

    @Test
    void unknownSourceIdGives404WithNoFeedRowCreatedAndNoFetchMade() throws Exception {
        String feedUrl = stub.baseUrl() + "/no-fetch.xml";
        String payload = """
                {
                    "url": "%s",
                    "name": "Should Fail",
                    "topic": "TECH",
                    "sourceId": 999999
                }
                """.formatted(feedUrl);

        adminPost(FEEDS, payload)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        assertThat(stub.requestsTo("/no-fetch.xml")).isEmpty();

        Long feedCount = jdbcClient.sql("SELECT count(*) FROM feed WHERE url = :url")
                .param("url", feedUrl)
                .query(Long.class)
                .single();
        assertThat(feedCount).isZero();
    }
}
