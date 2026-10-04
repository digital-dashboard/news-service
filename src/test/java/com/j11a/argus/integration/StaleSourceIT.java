package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An ingest that waits on a source lock while its feed moves away must follow the feed, or give up without blame. */
class StaleSourceIT extends AbstractIntegrationTest {

    private static final String PATH = "/stale/feed.xml";

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

    @Test
    void anIngestWhoseFeedMovesWhileItWaitsOnTheSourceLockLandsInTheNewSource() throws Exception {
        long feedId = createFeed();
        long oldSource = sourceOf(feedId);
        long newSource = sources.findOrCreate("moved.example", null).getId();
        stub.serve(PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://stale.example.test/", null, "old1", "new1"));
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CompletableFuture<IngestReport> ingesting;

        try (HeldLock mover = HeldLock.on(tx, sourceLock, oldSource, () -> merger.moveFeed(feedId, newSource))) {
            ingesting = CompletableFuture.supplyAsync(() -> ingest.refresh(feedId));
            awaitWaitingForSourceLock(oldSource);
        }

        IngestReport report = ingesting.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
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
        TransactionTemplate tx = new TransactionTemplate(txManager);

        try (LogCapture logs = LogCapture.start();
                HeldLock secondHolder = HeldLock.on(tx, sourceLock, second, () -> moveFeedRaw(feedId, third))) {
            HeldLock firstHolder = HeldLock.on(tx, sourceLock, first, () -> moveFeedRaw(feedId, second));
            CompletableFuture<IngestReport> ingesting = CompletableFuture.supplyAsync(() -> ingest.refresh(feedId));
            awaitWaitingForSourceLock(first);
            firstHolder.close();
            awaitWaitingForSourceLock(second);
            secondHolder.close();

            IngestReport report = ingesting.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);

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
    void aDeleteWhoseFeedMovesWhileItWaitsRetakesTheLockOfTheNewSourceBeforeItDeletesAnything() throws Exception {
        long feedId = createFeed();
        long oldSource = sourceOf(feedId);
        long newSource = sources.findOrCreate("moved-delete.example", null).getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CompletableFuture<Void> deleting;

        try (HeldLock newSourceHeld = HeldLock.on(tx, sourceLock, newSource)) {
            try (HeldLock mover = HeldLock.on(tx, sourceLock, oldSource, () -> moveFeedRaw(feedId, newSource))) {
                deleting = CompletableFuture.runAsync(() -> feedService.delete(feedId));
                awaitWaitingForSourceLock(oldSource);
            }

            // Only the re-read under the old source's lock notices the move and queues for the new source's lock.
            awaitWaitingForSourceLock(newSource);
            assertThat(deleting).isNotDone();
            assertThat(count("SELECT count(*) FROM feed WHERE id = " + feedId)).isOne();
        }

        deleting.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(count("SELECT count(*) FROM feed")).isZero();
        assertThat(count("SELECT count(*) FROM article")).isZero();
    }

    private void moveFeedRaw(long feedId, long sourceId) {
        jdbcClient.sql("UPDATE feed SET source_id = :source WHERE id = :id")
                .param("source", sourceId).param("id", feedId).update();
    }
}
