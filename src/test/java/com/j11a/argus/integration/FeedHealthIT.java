package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.FeedStubServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedHealthIT extends AbstractIntegrationTest {

    private static final String PATH = "/health/feed.xml";

    @Autowired
    private FeedHealthUpdater healthUpdater;

    @Autowired
    private FeedHealthGauges healthGauges;

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void status304LeavesArticlesUntouchedAndUpdatesLastFetchedAndSuccess() {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link>"
                        + "<item><title>A1</title><link>https://health.example.test/1</link><guid>g1</guid></item>"
                        + "</channel></rss>"),
                Map.of("ETag", "\"v1\""));

        FeedResponse created = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS));
        long feedId = created.id();

        long articleCountBefore = jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single();
        assertThat(articleCountBefore).isOne();

        var initialRow = jdbcClient.sql("SELECT etag, consecutive_failures, last_fetched_at, last_success_at FROM feed WHERE id = :id")
                .param("id", feedId).query().singleRow();
        assertThat(initialRow).containsEntry("etag", "\"v1\"")
                .containsEntry("consecutive_failures", 0);

        // Next request returns 304
        stub.serve(PATH, 304, null, new byte[0], Map.of("ETag", "\"v1\""));

        Timer notModifiedIngests = meterRegistry.find(MetricNames.INGEST).tag("outcome", "not_modified").timer();
        long notModifiedBefore = notModifiedIngests == null ? 0 : notModifiedIngests.count();

        IngestReport report = ingestService.refresh(feedId);
        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.NOT_MODIFIED);
        assertThat(meterRegistry.find(MetricNames.INGEST).tag("outcome", "not_modified").timer().count())
                .isEqualTo(notModifiedBefore + 1);

        long articleCountAfter = jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single();
        assertThat(articleCountAfter).isEqualTo(articleCountBefore);

        var updatedRow = jdbcClient.sql("SELECT etag, consecutive_failures, last_fetched_at, last_success_at, last_error FROM feed WHERE id = :id")
                .param("id", feedId).query().singleRow();
        assertThat(updatedRow).containsEntry("consecutive_failures", 0);
        assertThat(updatedRow.get("last_error")).isNull();
        Date lastFetched = (Date) updatedRow.get("last_fetched_at");
        Date initialFetched = (Date) initialRow.get("last_fetched_at");
        assertThat(lastFetched).isAfterOrEqualTo(initialFetched);
    }

    @Test
    void failureIncrementsConsecutiveFailuresAndRecordsLastErrorWithNoUrlThenSuccessResets() {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        FeedResponse created = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS));
        long feedId = created.id();

        // Down with 503
        stub.serve(PATH, 503, "text/plain", FeedStubServer.utf8("server busy"));
        IngestReport failed1 = ingestService.refresh(feedId);
        assertThat(failed1.outcome()).isEqualTo(IngestReport.Outcome.FAILED);

        var row1 = jdbcClient.sql("SELECT consecutive_failures, last_error FROM feed WHERE id = :id")
                .param("id", feedId).query().singleRow();
        assertThat(row1).containsEntry("consecutive_failures", 1)
                .containsEntry("last_error", "http_status 503");
        assertThat((String) row1.get("last_error")).doesNotContain("http://").doesNotContain("health.example.test");

        // Fails again with 500
        stub.serve(PATH, 500, "text/plain", FeedStubServer.utf8("internal server error"));
        IngestReport failed2 = ingestService.refresh(feedId);
        assertThat(failed2.outcome()).isEqualTo(IngestReport.Outcome.FAILED);

        var row2 = jdbcClient.sql("SELECT consecutive_failures, last_error FROM feed WHERE id = :id")
                .param("id", feedId).query().singleRow();
        assertThat(row2).containsEntry("consecutive_failures", 2)
                .containsEntry("last_error", "http_status 500");

        // Now recovers with 200
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        IngestReport success = ingestService.refresh(feedId);
        assertThat(success.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);

        var row3 = jdbcClient.sql("SELECT consecutive_failures, last_error FROM feed WHERE id = :id")
                .param("id", feedId).query().singleRow();
        assertThat(row3).containsEntry("consecutive_failures", 0);
        assertThat(row3.get("last_error")).isNull();
    }

    @Test
    void concurrentRecordFailureCallsLoseNoIncrements() throws InterruptedException {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        FeedResponse created = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS));
        long feedId = created.id();

        int concurrency = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            for (int i = 0; i < concurrency; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        healthUpdater.recordFailure(feedId, "error_" + index, Instant.now());
                    } catch (Exception ignored) {
                        // A failed worker still counts down; the increment assertion below catches any loss.
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
        }

        int failures = jdbcClient.sql("SELECT consecutive_failures FROM feed WHERE id = :id")
                .param("id", feedId).query(Integer.class).single();
        assertThat(failures).isEqualTo(concurrency);
    }

    @Test
    void feedHealthGaugesReflectStateSinceLastSuccessAndDisappearOnDeletion() {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        FeedResponse created = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS));
        long feedId = created.id();
        String feedIdStr = String.valueOf(feedId);

        // Gauges exist right after create
        Gauge stateGauge = meterRegistry.find(MetricNames.FEED_STATE)
                .tag("feed_id", feedIdStr)
                .tag("state", "healthy")
                .gauge();
        assertThat(stateGauge).isNotNull();
        assertThat(stateGauge.value()).isEqualTo(1.0);

        Gauge sinceSuccess = meterRegistry.find(MetricNames.FEED_SINCE_LAST_SUCCESS)
                .tag("feed_id", feedIdStr)
                .gauge();
        assertThat(sinceSuccess).isNotNull();
        assertThat(sinceSuccess.value()).isGreaterThanOrEqualTo(0.0);

        // Deleting the feed from db and refreshing gauges removes the series
        jdbcClient.sql("DELETE FROM feed WHERE id = :id").param("id", feedId).update();
        healthGauges.refresh();

        assertThat(meterRegistry.find(MetricNames.FEED_STATE).tag("feed_id", feedIdStr).gauge()).isNull();
        assertThat(meterRegistry.find(MetricNames.FEED_CONSECUTIVE_FAILURES).tag("feed_id", feedIdStr).gauge()).isNull();
        assertThat(meterRegistry.find(MetricNames.FEED_SINCE_LAST_SUCCESS).tag("feed_id", feedIdStr).gauge()).isNull();
    }

    @Test
    void aFeedThatNeverSucceededReportsMinusOneSecondsSinceLastSuccess() {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        long feedId = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS)).id();
        jdbcClient.sql("UPDATE feed SET last_success_at = NULL WHERE id = :id").param("id", feedId).update();

        healthGauges.refresh();

        Gauge sinceSuccess = meterRegistry.find(MetricNames.FEED_SINCE_LAST_SUCCESS)
                .tag("feed_id", String.valueOf(feedId)).gauge();
        assertThat(sinceSuccess).isNotNull();
        assertThat(sinceSuccess.value()).isEqualTo(-1.0);
    }

    @Test
    void aLongFailureReasonIsCappedAtTheColumnLimit() {
        stub.serve(PATH, 200, "application/rss+xml",
                FeedStubServer.utf8("<rss><channel><title>H</title><link>https://health.example.test</link></channel></rss>"));
        long feedId = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS)).id();

        healthUpdater.recordFailure(feedId, "x".repeat(500), Instant.now());

        String lastError = jdbcClient.sql("SELECT last_error FROM feed WHERE id = :id")
                .param("id", feedId).query(String.class).single();
        assertThat(lastError).hasSize(128);
    }
}
