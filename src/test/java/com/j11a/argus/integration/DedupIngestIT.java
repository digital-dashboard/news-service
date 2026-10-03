package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.IngestMeters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DedupIngestIT extends AbstractIntegrationTest {

    private static final String ARTICLES = "/news/v2/articles";
    private static final String FEEDS = "/news/v2/feeds";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MeterRegistry registry;

    private IngestMeters meters() {
        return new IngestMeters(registry);
    }

    private JsonNode refresh(long feedId) throws Exception {
        String body = mockMvc.perform(post(FEEDS + "/" + feedId + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private List<OffsetDateTime> modifiedAts() {
        return jdbcClient.sql("SELECT modified_at FROM article ORDER BY id").query(OffsetDateTime.class).list();
    }

    private String titlesUnder(Topic topic) throws Exception {
        return mockMvc.perform(get(ARTICLES).param("topic", topic.name())).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theSameArticleInTwoFeedsOfOneSourceIsStoredOnceAndLinkedTwice() throws Exception {
        FeedResponse news = createFeedFrom("/dedup/news.xml", "p4-shared-news.xml", Topic.NEWS);
        double linkedBefore = meters().entries(news.source().name(), "linked", "none");

        createFeedFrom("/dedup/world.xml", "p4-shared-world.xml", Topic.WORLD);

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM article_feed")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM (SELECT article_id FROM article_feed GROUP BY article_id "
                + "HAVING count(*) = 2) shared")).isEqualTo(1);
        assertThat(meters().entries(news.source().name(), "linked", "none")).isEqualTo(linkedBefore + 1);
        assertThat(titlesUnder(Topic.NEWS)).contains("Shared Article").contains("Unique News Article");
        assertThat(titlesUnder(Topic.WORLD)).contains("Shared Article").contains("Unique World Article");
    }

    @Test
    void aChangedGuidWithTheSameLinkUpdatesTheExistingArticle() throws Exception {
        FeedResponse feed = createFeedFrom("/dedup/guid.xml", "p4-guid-v1.xml", Topic.NEWS);
        String source = feed.source().name();
        double replacedBefore = meters().fallback(source, "guid_replaced");
        stub.serveFixture("/dedup/guid.xml", "p4-guid-v2.xml");

        JsonNode report = refresh(feed.id());

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT guid_key FROM article").query(String.class).single()).isEqualTo("guid-new-2");
        assertThat(report.path("unchanged").asInt()).isEqualTo(1);
        assertThat(meters().fallback(source, "guid_replaced")).isEqualTo(replacedBefore + 1);
    }

    @Test
    void entriesAllLinkingToTheHomepageStayDistinct() {
        double guardedBefore = meters().fallback("blog.example.test", "guarded_homepage");

        createFeedFrom("/dedup/home.xml", "p4-homepage-links.xml", Topic.NEWS);

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(3);
        assertThat(meters().fallback("blog.example.test", "guarded_homepage")).isEqualTo(guardedBefore + 3);
    }

    @Test
    void anEditedArticleKeepsItsIdAndFetchTimeAndIsCountedAsContentChanged() throws Exception {
        FeedResponse feed = createFeedFrom("/dedup/edit.xml", "p4-edited-v1.xml", Topic.NEWS);
        String source = feed.source().name();
        long idBefore = count("SELECT id FROM article");
        OffsetDateTime fetchedBefore = jdbcClient.sql("SELECT fetched_at FROM article").query(OffsetDateTime.class).single();
        double editsBefore = meters().entries(source, "updated", "content_changed");
        stub.serveFixture("/dedup/edit.xml", "p4-edited-v2.xml");

        JsonNode report = refresh(feed.id());

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(1);
        assertThat(count("SELECT id FROM article")).isEqualTo(idBefore);
        assertThat(jdbcClient.sql("SELECT title FROM article").query(String.class).single()).isEqualTo("Edited Title");
        assertThat(jdbcClient.sql("SELECT fetched_at FROM article").query(OffsetDateTime.class).single())
                .isEqualTo(fetchedBefore);
        assertThat(report.path("updated").asInt()).isEqualTo(1);
        assertThat(meters().entries(source, "updated", "content_changed")).isEqualTo(editsBefore + 1);
    }

    @Test
    void aTimestampOnlyEditAdvancesTheUpstreamTimeWithoutTouchingTheTitle() throws Exception {
        FeedResponse feed = createFeedFrom("/dedup/ts.xml", "p4-timestamp-v1.xml", Topic.NEWS);
        String source = feed.source().name();
        OffsetDateTime upstreamBefore = jdbcClient.sql("SELECT updated_at_upstream FROM article")
                .query(OffsetDateTime.class).single();
        double before = meters().entries(source, "updated", "timestamp_only");
        stub.serveFixture("/dedup/ts.xml", "p4-timestamp-v2.xml");

        JsonNode report = refresh(feed.id());

        OffsetDateTime upstreamAfter = jdbcClient.sql("SELECT updated_at_upstream FROM article")
                .query(OffsetDateTime.class).single();
        assertThat(upstreamAfter).isAfter(upstreamBefore);
        assertThat(jdbcClient.sql("SELECT title FROM article").query(String.class).single()).isEqualTo("Stable Content");
        assertThat(report.path("updated").asInt()).isEqualTo(1);
        assertThat(meters().entries(source, "updated", "timestamp_only")).isEqualTo(before + 1);
    }

    @Test
    void refreshingAnUnchangedFeedWritesNothing() throws Exception {
        FeedResponse feed = createFeedFrom("/dedup/steady.xml", "p4-shared-news.xml", Topic.NEWS);
        List<OffsetDateTime> modifiedBefore = modifiedAts();

        JsonNode report = refresh(feed.id());

        assertThat(report.path("unchanged").asInt()).isEqualTo(2);
        assertThat(report.path("entriesSeen").asInt()).isEqualTo(2);
        assertThat(modifiedAts()).isEqualTo(modifiedBefore);
    }

    @Test
    void aGuidRepeatedInOneDownloadIsStoredOnceAndTheCopyIsSkipped() {
        double before = meters().entries("atom.example.test", "skipped", "batch_duplicate");

        createFeedFrom("/dedup/dup.xml", "p4-batch-dup.xml", Topic.NEWS);

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT title FROM article").query(String.class).single())
                .isEqualTo("Second Version in Batch");
        assertThat(meters().entries("atom.example.test", "skipped", "batch_duplicate")).isEqualTo(before + 1);
    }

    @Test
    void twoFeedsShowingDifferentCopiesOfOneArticleNeverFlipItBack() throws Exception {
        FeedResponse feedA = createFeedFrom("/dedup/a.xml", "p4-edited-v1.xml", Topic.NEWS);
        String source = feedA.source().name();
        double linkedBefore = meters().entries(source, "linked", "none");
        double updatedBefore = meters().entries("updated");

        FeedResponse feedB = createFeedFrom("/dedup/b.xml", "p4-edited-v2.xml", Topic.WORLD);

        assertThat(meters().entries(source, "linked", "none")).isEqualTo(linkedBefore + 1);
        assertThat(jdbcClient.sql("SELECT title FROM article").query(String.class).single()).isEqualTo("Original Title");
        int updatedByRefreshes = 0;
        for (int round = 0; round < 2; round++) {
            for (long feedId : List.of(feedA.id(), feedB.id())) {
                updatedByRefreshes += refresh(feedId).path("updated").asInt();
            }
        }
        assertThat(updatedByRefreshes).isZero();
        assertThat(meters().entries("updated")).isEqualTo(updatedBefore);
        assertThat(jdbcClient.sql("SELECT title FROM article").query(String.class).single()).isEqualTo("Original Title");
    }
}
