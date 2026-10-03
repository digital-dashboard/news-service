package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.testsupport.FeedStubServer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConcurrentIngestIT extends AbstractIntegrationTest {

    private static final int ITEMS = 20;
    private static final int ROUNDS = 5;
    private static final String SOURCE = "conc.example.test";
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(20);

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private SourceLock sourceLock;

    @Autowired
    private PlatformTransactionManager txManager;

    private static byte[] rss(int items) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title>")
                .append("<link>https://").append(SOURCE).append("/</link><description>d</description>");
        IntStream.range(0, items).forEach(i -> xml.append("<item><title>Item ").append(i)
                .append("</title><link>https://").append(SOURCE).append("/n/").append(i).append("</link><guid>g-")
                .append(i).append("</guid></item>"));
        return FeedStubServer.utf8(xml.append("</channel></rss>").toString());
    }

    private void serveRss(String path, int items) {
        stub.serve(path, 200, "application/rss+xml", rss(items));
    }

    private FeedResponse createSmallFeed(String path) {
        serveRss(path, 1);
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null,
                Topic.NEWS, null));
    }

    @Test
    void twoFeedsOfOneSourceIngestedAtTheSameTimeNeverStoreADuplicate() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUNDS; round++) {
                jdbcClient.sql("TRUNCATE article_feed, article, feed, source RESTART IDENTITY CASCADE").update();
                FeedResponse a = createSmallFeed("/conc/a.xml");
                FeedResponse b = createSmallFeed("/conc/b.xml");
                serveRss("/conc/a.xml", ITEMS);
                serveRss("/conc/b.xml", ITEMS);
                long waitsBefore = meters().lockWaits(SOURCE);
                double conflictsBefore = meters().entries(SOURCE, "updated", "insert_conflict");
                CyclicBarrier barrier = new CyclicBarrier(2);

                List<Future<IngestReport>> reports = List.of(a.id(), b.id()).stream()
                        .map(id -> pool.submit(() -> {
                            barrier.await();
                            return ingestService.refresh(id);
                        }))
                        .toList();
                int insertedByBoth = 0;
                for (Future<IngestReport> report : reports) {
                    IngestReport result = report.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
                    assertThat(result.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
                    insertedByBoth += result.inserted();
                }

                // Serialised: the first feed inserts every new item, the second only links to them. Both feeds
                // already hold item 0 from createSmallFeed. Without the lock the race losers would instead be
                // absorbed as insert conflicts and the sum would fall short.
                assertThat(insertedByBoth).isEqualTo(ITEMS - 1);
                assertThat(meters().entries(SOURCE, "updated", "insert_conflict")).isEqualTo(conflictsBefore);
                assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM (SELECT 1 FROM article GROUP BY source_id, guid_key
                        HAVING count(*) > 1) duplicates""").query(Long.class).single()).isZero();
                assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isEqualTo(ITEMS);
                assertThat(meters().lockWaits(SOURCE)).isGreaterThanOrEqualTo(waitsBefore + 2);
            }
        }
    }

    @Test
    void aRefreshWaitsForAHeldSourceLockAndTheWaitIsRecorded() throws Exception {
        FeedResponse feed = createSmallFeed("/conc/held.xml");
        long sourceId = feed.source().id();
        double waitedBefore = meters().lockWaitSeconds(SOURCE);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                sourceLock.acquire(sourceId);
                holding.countDown();
                awaitQuietly(release);
            }));
            assertThat(holding.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            Future<IngestReport> refresh = pool.submit(() -> ingestService.refresh(feed.id()));

            await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(advisoryWaiters()).isEqualTo(1));
            assertThat(refresh.isDone()).isFalse();
            release.countDown();

            assertThat(refresh.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS).outcome())
                    .isEqualTo(IngestReport.Outcome.COMPLETED);
            holder.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(meters().lockWaitMaxNanos(SOURCE)).isGreaterThan(0);
        assertThat(meters().lockWaitSeconds(SOURCE)).isGreaterThan(waitedBefore);
    }

    private long advisoryWaiters() {
        return jdbcClient.sql("SELECT count(*) FROM pg_stat_activity "
                + "WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'").query(Long.class).single();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
