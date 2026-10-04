package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedService;
import com.j11a.argus.feed.api.PatchFeedRequest;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.RssBody;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class FeedPatchIT extends AbstractIntegrationTest {

    private static final String SITE = "https://patch.example.test/";

    @Autowired
    private SourceService sources;

    private String url(String path) {
        return stub.baseUrl() + path;
    }

    private long create(String path, String... slugs) {
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, slugs), Map.of("ETag", "\"v1\""));
        return feedService.create(new CreateFeedRequest(url(path), null, Topic.NEWS, null)).id();
    }

    private ResultActions patchFeed(long id, String json) throws Exception {
        return mockMvc.perform(patch("/news/v2/feeds/" + id).header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String column(long id, String column) {
        return jdbcClient.sql("SELECT " + column + " FROM feed WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getString(1)).list().getFirst();
    }

    @Test
    void enabledAloneDisablesTheFeed() throws Exception {
        long id = create("/patch/enabled.xml", "e1");

        patchFeed(id, "{\"enabled\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.state").value("disabled"));
    }

    @Test
    void nameAloneIsStrippedAndStored() throws Exception {
        long id = create("/patch/name.xml", "n1");

        patchFeed(id, "{\"name\":\"  Renamed feed  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed feed"));
        assertThat(column(id, "topic")).isEqualTo("NEWS");
    }

    @Test
    void topicAloneIsStored() throws Exception {
        long id = create("/patch/topic.xml", "t1");

        patchFeed(id, "{\"topic\":\"TECH\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("TECH"));
        assertThat(column(id, "enabled")).isEqualTo("t");
    }

    @Test
    void aNewUrlIsDownloadedStoredWithItsSelfLinkAndClearsTheValidatorsButKeepsHealth() throws Exception {
        long id = create("/patch/url-old.xml", "u1");
        jdbcClient.sql("UPDATE feed SET last_modified = 'Wed, 21 Oct 2026 07:28:00 GMT', consecutive_failures = 2,"
                + " last_error = 'io' WHERE id = :id").param("id", id).update();
        assertThat(column(id, "etag")).isEqualTo("\"v1\"");
        String newPath = "/patch/url-new.xml";
        String self = url("/patch/url-self.xml");
        stub.serve(newPath, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, self, "u1"));

        patchFeed(id, "{\"url\":\"" + url(newPath) + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(url(newPath)));

        assertThat(column(id, "url")).isEqualTo(url(newPath));
        assertThat(column(id, "self_url")).isEqualTo(self);
        assertThat(column(id, "etag")).isNull();
        assertThat(column(id, "last_modified")).isNull();
        assertThat(column(id, "consecutive_failures")).isEqualTo("2");
        assertThat(column(id, "last_error")).isEqualTo("io");
    }

    @Test
    void aUrlThatCleansToTheStoredOneIsANoOpWithoutADownload() throws Exception {
        long id = create("/patch/same.xml", "s1");
        int requestsBefore = stub.requests().size();

        patchFeed(id, "{\"url\":\"" + url("/patch/same.xml").replace("http://", "HTTP://") + "#frag\"}")
                .andExpect(status().isOk());

        assertThat(stub.requests()).hasSize(requestsBefore);
        assertThat(column(id, "etag")).isEqualTo("\"v1\"");
    }

    @Test
    void everyFieldTogetherAppliesAndTheFeedIsReturnedInItsNewSource() throws Exception {
        long id = create("/patch/all-old.xml", "c1");
        long target = sources.findOrCreate("target.example", null).getId();
        String newPath = "/patch/all-new.xml";
        stub.serve(newPath, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, "c1"));

        patchFeed(id, "{\"enabled\":false,\"name\":\"All\",\"topic\":\"WORLD\",\"sourceId\":" + target
                + ",\"url\":\"" + url(newPath) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.name").value("All"))
                .andExpect(jsonPath("$.topic").value("WORLD"))
                .andExpect(jsonPath("$.url").value(url(newPath)))
                .andExpect(jsonPath("$.source.id").value(target));
    }

    @Test
    void anEmptyBodyIs400AndNothingChanges() throws Exception {
        long id = create("/patch/empty.xml", "m1");

        patchFeed(id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].message").value("at least one field must be provided"));
    }

    @Test
    void aBlankNameIs400() throws Exception {
        long id = create("/patch/blank.xml", "b1");

        patchFeed(id, "{\"name\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void anUnknownFeedIs404() throws Exception {
        patchFeed(99_999L, "{\"enabled\":false}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void aUrlThatNamesAnotherFeedIs409KindEnteredAndNothingChanges() throws Exception {
        long other = create("/patch/entered-other.xml", "o1");
        long id = create("/patch/entered-own.xml", "o2");

        patchFeed(id, "{\"name\":\"Nope\",\"url\":\"" + url("/patch/entered-other.xml") + "/\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingFeedId").value(other))
                .andExpect(jsonPath("$.kind").value("entered"));

        assertThat(column(id, "name")).isNotEqualTo("Nope");
        assertThat(column(id, "url")).isEqualTo(url("/patch/entered-own.xml"));
    }

    @Test
    void aUrlThatRedirectsToAnotherFeedIs409KindRedirect() throws Exception {
        long other = create("/patch/redirect-other.xml", "r1");
        long id = create("/patch/redirect-own.xml", "r2");
        stub.redirect("/patch/redirect-moved.xml", 302, url("/patch/redirect-other.xml"));

        patchFeed(id, "{\"url\":\"" + url("/patch/redirect-moved.xml") + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingFeedId").value(other))
                .andExpect(jsonPath("$.kind").value("redirect"));
        assertThat(column(id, "url")).isEqualTo(url("/patch/redirect-own.xml"));
    }

    @Test
    void aFeedWhoseSelfLinkIsAnotherFeedIs409KindSelfLink() throws Exception {
        long other = create("/patch/self-other.xml", "p1");
        long id = create("/patch/self-own.xml", "p2");
        stub.serve("/patch/self-new.xml", 200, RssBody.CONTENT_TYPE,
                RssBody.rss(SITE, url("/patch/self-other.xml"), "p3"));

        patchFeed(id, "{\"url\":\"" + url("/patch/self-new.xml") + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingFeedId").value(other))
                .andExpect(jsonPath("$.kind").value("self_link"));
        assertThat(column(id, "url")).isEqualTo(url("/patch/self-own.xml"));
    }

    @Test
    void aSelfLinkOrRedirectNamingTheFeedItselfIsNotAConflict() throws Exception {
        long id = create("/patch/own-old.xml", "q1");
        stub.serve("/patch/own-new.xml", 200, RssBody.CONTENT_TYPE,
                RssBody.rss(SITE, url("/patch/own-old.xml"), "q1"));

        patchFeed(id, "{\"url\":\"" + url("/patch/own-new.xml") + "\"}").andExpect(status().isOk());

        assertThat(column(id, "url")).isEqualTo(url("/patch/own-new.xml"));
        assertThat(column(id, "self_url")).isEqualTo(url("/patch/own-old.xml"));
    }

    @Test
    void aUnreadableFeedAtTheNewUrlIs422AndEvenTheOtherFieldsAreNotApplied() throws Exception {
        long id = create("/patch/invalid-own.xml", "i1");
        stub.serve("/patch/invalid-new.xml", 200, "text/html", "<html><body>no feed</body></html>".getBytes());

        patchFeed(id, "{\"name\":\"Nope\",\"enabled\":false,\"url\":\"" + url("/patch/invalid-new.xml") + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FEED_INVALID"));

        assertThat(column(id, "name")).isNotEqualTo("Nope");
        assertThat(column(id, "enabled")).isEqualTo("t");
        assertThat(column(id, "url")).isEqualTo(url("/patch/invalid-own.xml"));
    }

    @Test
    void anUnknownTargetSourceIs404AndNothingIsApplied() throws Exception {
        long id = create("/patch/nosource.xml", "x1");

        patchFeed(id, "{\"name\":\"Nope\",\"sourceId\":987654}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        assertThat(column(id, "name")).isNotEqualTo("Nope");
    }

    @Test
    void movingAFeedMovesItsExclusiveArticlesAndDeletesTheEmptiedSource() throws Exception {
        long id = create("/patch/move.xml", "m1", "m2");
        long oldSource = Long.parseLong(column(id, "source_id"));
        long target = sources.findOrCreate("target.example", null).getId();

        patchFeed(id, "{\"sourceId\":" + target + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.source.id").value(target));

        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + target)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + oldSource)).isZero();
        assertThat(count("SELECT count(*) FROM source WHERE id = " + oldSource)).isZero();
    }

    @Test
    void movingOneOfTwoFeedsThatShareArticlesCopiesThemLikeTheMerger() throws Exception {
        long staying = create("/patch/share-a.xml", "z1", "z2");
        long moving = create("/patch/share-b.xml", "z1", "z2");
        long oldSource = Long.parseLong(column(staying, "source_id"));
        long target = sources.findOrCreate("target-two.example", null).getId();
        assertThat(count("SELECT count(*) FROM article")).isEqualTo(2);

        patchFeed(moving, "{\"sourceId\":" + target + "}").andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + oldSource)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + target)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article_feed af JOIN article a ON a.id = af.article_id "
                + "WHERE af.feed_id = " + moving + " AND a.source_id = " + target)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM source WHERE id = " + oldSource)).isOne();
    }

    @Test
    void aUrlChangeLogsOneRedactedInfoLineAndFieldChangesLogTheirOwn() {
        long id = create("/patch/log-old.xml", "l1");
        String newPath = "/patch/log-new.xml";
        stub.serve(newPath, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, "l1"));

        try (LogCapture logs = LogCapture.start()) {
            feedService.patch(id, new PatchFeedRequest(null, "Logged", Topic.TECH, null, url(newPath) + "?token=SECRET"));

            assertThat(logs.at(Level.INFO, FeedService.class)).hasSize(2).satisfiesExactly(
                    changed -> assertThat(LogCapture.keyValues(changed))
                            .containsEntry("feedId", id)
                            .containsEntry("url", url("/patch/log-old.xml"))
                            .containsEntry("newUrl", url(newPath)),
                    updated -> assertThat(LogCapture.keyValues(updated))
                            .containsEntry("newName", "Logged")
                            .containsEntry("newTopic", "TECH")
                            .containsEntry("changedFields", java.util.List.of("name", "topic")));
            logs.assertNothingLogged("SECRET");
        }
    }
}
