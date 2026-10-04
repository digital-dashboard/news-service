package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.RssBody;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class FeedRedirectPollingIT extends AbstractIntegrationTest {

    private static final String SITE = "https://redirects.example.test/";

    @Autowired
    private FeedIngestService ingest;

    private long create(String path, String... slugs) {
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, slugs), Map.of("ETag", "\"v1\""));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null, Topic.NEWS, null)).id();
    }

    private String urlOf(long id) {
        return jdbcClient.sql("SELECT url FROM feed WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String selfUrlOf(long id) {
        return jdbcClient.sql("SELECT self_url FROM feed WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getString(1)).single();
    }

    private void redirectTo(String oldPath, int status, String newPath) {
        stub.redirect(oldPath, status, stub.baseUrl() + newPath);
        stub.serve(newPath, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, "a1"), Map.of("ETag", "\"v2\""));
    }

    @ParameterizedTest(name = "a {0} redirect moves the feed")
    @ValueSource(ints = {301, 308})
    void aPermanentRedirectUpdatesTheStoredUrlOnPoll(int status) {
        String oldPath = "/redir/perm-" + status + "/old.xml";
        String newPath = "/redir/perm-" + status + "/new.xml";
        long id = create(oldPath, "a1");
        redirectTo(oldPath, status, newPath);
        double before = meters().counter("argus.feed.redirect", "source", "redirects.example.test",
                "outcome", "permanent_applied");

        IngestReport report = ingest.refresh(id);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        assertThat(urlOf(id)).isEqualTo(stub.baseUrl() + newPath);
        assertThat(meters().counter("argus.feed.redirect", "source", "redirects.example.test",
                "outcome", "permanent_applied")).isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "a {0} redirect leaves the stored url alone")
    @ValueSource(ints = {302, 307})
    void aTemporaryRedirectDoesNotChangeTheStoredUrl(int status) {
        String oldPath = "/redir/temp-" + status + "/old.xml";
        String newPath = "/redir/temp-" + status + "/new.xml";
        long id = create(oldPath, "a1");
        redirectTo(oldPath, status, newPath);

        ingest.refresh(id);

        assertThat(urlOf(id)).isEqualTo(stub.baseUrl() + oldPath);
    }

    @Test
    void aNotModifiedAnswerReachedThroughAPermanentRedirectStillAppliesIt() {
        String oldPath = "/redir/notmod/old.xml";
        String newPath = "/redir/notmod/new.xml";
        long id = create(oldPath, "a1");
        stub.redirect(oldPath, 301, stub.baseUrl() + newPath);
        stub.serve(newPath, 304, null, new byte[0]);

        IngestReport report = ingest.refresh(id);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.NOT_MODIFIED);
        assertThat(urlOf(id)).isEqualTo(stub.baseUrl() + newPath);
    }

    @Test
    void aRedirectOntoAnotherFeedDisablesTheFeedWithoutPersistingOrCountingAFailure() throws Exception {
        String keptPath = "/redir/dup/kept.xml";
        String dupPath = "/redir/dup/dup.xml";
        long kept = create(keptPath, "k1", "k2", "k3");
        long duplicate = create(dupPath, "d1");
        long articlesBefore = count("SELECT count(*) FROM article");
        long linksBefore = count("SELECT count(*) FROM article_feed WHERE feed_id = " + duplicate);
        stub.redirect(dupPath, 301, stub.baseUrl() + keptPath);

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = ingest.refresh(duplicate);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
            assertThat(report.failureReason()).isEqualTo("duplicate_feed");
            assertThat(logs.at(Level.WARN)).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("Feed " + duplicate + " disabled"));
        }

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(articlesBefore);
        assertThat(count("SELECT count(*) FROM article_feed WHERE feed_id = " + duplicate)).isEqualTo(linksBefore);
        assertThat(jdbcClient.sql("SELECT last_error FROM feed WHERE id = :id").param("id", duplicate)
                .query(String.class).single()).isEqualTo("duplicate of feed " + kept);
        assertThat(count("SELECT consecutive_failures FROM feed WHERE id = " + duplicate)).isZero();
        assertThat(urlOf(duplicate)).isEqualTo(stub.baseUrl() + dupPath);
        mockMvc.perform(get("/news/v2/feeds/" + duplicate).header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("disabled"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.lastError").value("duplicate of feed " + kept));
    }

    @Test
    void aMissingSelfUrlIsBackfilledAfterASuccessfulPoll() {
        String path = "/redir/backfill/feed.xml";
        String self = stub.baseUrl() + "/redir/backfill/self.xml";
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, self, "a1"));
        long id = feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null, Topic.NEWS, null)).id();
        jdbcClient.sql("UPDATE feed SET self_url = NULL WHERE id = :id").param("id", id).update();

        ingest.refresh(id);

        assertThat(selfUrlOf(id)).isEqualTo(self);
    }
}
