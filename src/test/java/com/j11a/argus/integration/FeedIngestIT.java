package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedIngestIT extends AbstractIntegrationTest {

    private static final String PATH = "/ingest/feed.xml";

    @Autowired
    private FeedIngestService ingestService;

    private FeedResponse createFrom(String fixture) {
        return createFeedFrom(PATH, fixture, Topic.WORLD);
    }

    private long articleCount() {
        return jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single();
    }

    @Test
    void creatingAFeedIngestsItsEntriesWithoutASecondDownload() {
        int requestsBefore = stub.requestsTo(PATH).size();

        createFrom("bbc-like-rss2.xml");

        assertThat(articleCount()).isEqualTo(2);
        assertThat(stub.requestsTo(PATH)).hasSize(requestsBefore + 1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article_feed").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void refreshingTwiceStoresEachEntryOnce() {
        FeedResponse feed = createFrom("bbc-like-rss2.xml");

        IngestReport first = ingestService.refresh(feed.id());
        IngestReport second = ingestService.refresh(feed.id());

        assertThat(first.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        assertThat(second.entriesSeen()).isEqualTo(2);
        assertThat(second.inserted()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(articleCount()).isEqualTo(2);
    }

    @Test
    void anEntryWithNeitherGuidNorLinkIsSkipped() {
        FeedResponse feed = createFrom("missing-guid-date.xml");

        IngestReport report = ingestService.refresh(feed.id());

        assertThat(report.entriesSeen()).isEqualTo(3);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(2);
        assertThat(articleCount()).isEqualTo(2);
    }

    @Test
    void aFailingUpstreamGivesAFailedReportAndLeavesArticlesAlone() {
        FeedResponse feed = createFrom("bbc-like-rss2.xml");
        stub.serve(PATH, 404, "text/plain", new byte[0]);

        IngestReport report = ingestService.refresh(feed.id());

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.failureReason()).isEqualTo("http_status");
        assertThat(report.inserted()).isZero();
        assertThat(articleCount()).isEqualTo(2);
    }

    @Test
    void anUnparseableBodyGivesAFailedReportWithTheParseReason() {
        FeedResponse feed = createFrom("bbc-like-rss2.xml");
        stub.serve(PATH, 200, "text/html", Fixtures.feed("not-a-feed.html"));

        IngestReport report = ingestService.refresh(feed.id());

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.failureReason()).isEqualTo("not_a_feed");
        assertThat(articleCount()).isEqualTo(2);
    }

    @Test
    void entriesWithALinkThatIsNotHttpAreStoredWithoutTheLink() {
        createFrom("unsafe-link.xml");

        assertThat(articleCount()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article WHERE link IS NULL AND link_key IS NULL")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void articlesDatedInTheFutureAreStoredWithTheFetchTimeAsEffectiveTime() {
        createFrom("future-dated.xml");

        Long dated2100 = jdbcClient.sql("SELECT count(*) FROM article WHERE published_at > fetched_at")
                .query(Long.class).single();
        Long cappedToFetch = jdbcClient.sql("SELECT count(*) FROM article WHERE effective_at = fetched_at")
                .query(Long.class).single();

        assertThat(dated2100).isEqualTo(2);
        assertThat(cappedToFetch).isEqualTo(2);
    }
}
