package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.RssBody;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An ingest that waits on a source lock while its feed moves away must follow the feed, or give up without blame. */
class StaleSourceIT extends AbstractIntegrationTest {

    private static final String PATH = "/stale/feed.xml";
    private static final int SOURCE_LOCK_NAMESPACE = 4100;
    private static final long WAIT_SECONDS = 20;

    @Autowired
    private FeedIngestService ingest;

    @Autowired
    private SourceMerger merger;

    @Autowired
    private SourceLock sourceLock;

    @Autowired
    private SourceService sources;

    @Autowired
    private PlatformTransactionManager txManager;

    private long createFeed() {
        stub.serve(PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://stale.example.test/", null, "old1"));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null)).id();
    }

    private long sourceOf(long feedId) {
        return count("SELECT source_id FROM feed WHERE id = " + feedId);
    }

    /** Holds the source lock in a transaction until released, then runs beforeCommit and commits. */
    private CompletableFuture<Void> holdLock(long sourceId, CountDownLatch acquired, CountDownLatch release,
            Runnable beforeCommit) {
        return CompletableFuture.runAsync(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            sourceLock.acquire(sourceId);
            acquired.countDown();
            awaitLatch(release);
            beforeCommit.run();
        }));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitWaitingFor(long sourceId) {
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> count(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = "
                        + SOURCE_LOCK_NAMESPACE + " AND objid = " + sourceId) > 0);
    }

    @Test
    void anIngestWhoseFeedMovesWhileItWaitsOnTheSourceLockLandsInTheNewSource() throws Exception {
        long feedId = createFeed();
        long oldSource = sourceOf(feedId);
        long newSource = sources.findOrCreate("moved.example", null).getId();
        stub.serve(PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://stale.example.test/", null, "old1", "new1"));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> mover = holdLock(oldSource, acquired, release, () -> merger.moveFeed(feedId, newSource));
        awaitLatch(acquired);

        CompletableFuture<IngestReport> ingesting = CompletableFuture.supplyAsync(() -> ingest.refresh(feedId));
        awaitWaitingFor(oldSource);
        release.countDown();

        IngestReport report = ingesting.get(WAIT_SECONDS, TimeUnit.SECONDS);
        mover.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        assertThat(report.inserted()).isOne();
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + newSource)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + oldSource)).isZero();
        assertThat(count("SELECT consecutive_failures FROM feed WHERE id = " + feedId)).isZero();
    }

    @Test
    void aFeedThatMovesAgainBeforeTheRetryFailsWithSourceChangedAndNoFailureIsCounted() throws Exception {
        long feedId = createFeed();
        long first = sourceOf(feedId);
        long second = sources.findOrCreate("second.example", null).getId();
        long third = sources.findOrCreate("third.example", null).getId();
        stub.serve(PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://stale.example.test/", null, "old1", "new1"));
        long articlesBefore = count("SELECT count(*) FROM article");
        CountDownLatch secondHeld = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CompletableFuture<Void> secondHolder = holdLock(second, secondHeld, releaseSecond,
                () -> moveFeedRaw(feedId, third));
        awaitLatch(secondHeld);
        CountDownLatch firstHeld = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Void> firstHolder = holdLock(first, firstHeld, releaseFirst,
                () -> moveFeedRaw(feedId, second));
        awaitLatch(firstHeld);

        try (LogCapture logs = LogCapture.start()) {
            CompletableFuture<IngestReport> ingesting = CompletableFuture.supplyAsync(() -> ingest.refresh(feedId));
            awaitWaitingFor(first);
            releaseFirst.countDown();
            awaitWaitingFor(second);
            releaseSecond.countDown();

            IngestReport report = ingesting.get(WAIT_SECONDS, TimeUnit.SECONDS);
            firstHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            secondHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
            assertThat(report.failureReason()).isEqualTo("source_changed");
            assertThat(logs.at(Level.WARN)).anySatisfy(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("reason", "source_changed"));
        }
        assertThat(count("SELECT consecutive_failures FROM feed WHERE id = " + feedId)).isZero();
        assertThat(count("SELECT count(*) FROM feed WHERE last_error IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM article")).isEqualTo(articlesBefore);
    }

    @Test
    void aDeleteWhoseFeedMovesWhileItWaitsOnTheSourceLockStillRemovesTheFeedAndItsArticles() throws Exception {
        long feedId = createFeed();
        long oldSource = sourceOf(feedId);
        long newSource = sources.findOrCreate("moved-delete.example", null).getId();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> mover = holdLock(oldSource, acquired, release, () -> merger.moveFeed(feedId, newSource));
        awaitLatch(acquired);

        CompletableFuture<Void> deleting = CompletableFuture.runAsync(() -> feedService.delete(feedId));
        awaitWaitingFor(oldSource);
        release.countDown();

        deleting.get(WAIT_SECONDS, TimeUnit.SECONDS);
        mover.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(count("SELECT count(*) FROM feed")).isZero();
        assertThat(count("SELECT count(*) FROM article")).isZero();
    }

    private void moveFeedRaw(long feedId, long sourceId) {
        jdbcClient.sql("UPDATE feed SET source_id = :source WHERE id = :id")
                .param("source", sourceId).param("id", feedId).update();
    }
}
