package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.source.MoveResult;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FeedMoveIT extends AbstractIntegrationTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-01T10:00:00Z");
    private static final long WAIT_SECONDS = 20;

    @Autowired
    private SourceMerger merger;

    @Autowired
    private SourceLock sourceLock;

    @Autowired
    private PlatformTransactionManager txManager;

    private MergeData data;
    private long source;
    private long target;
    private long moving;
    private long staying;
    private long targetFeed;

    @BeforeEach
    void seed() {
        data = new MergeData(jdbcClient);
        source = data.source("bbci.co.uk", "https://www.bbci.co.uk");
        target = data.source("bbc.co.uk", "https://www.bbc.co.uk");
        moving = data.feed(source, "https://bbci.co.uk/moving");
        staying = data.feed(source, "https://bbci.co.uk/staying");
        targetFeed = data.feed(target, "https://bbc.co.uk/feed");
    }

    private List<Long> linkedFeeds(long articleId) {
        return jdbcClient.sql("SELECT feed_id FROM article_feed WHERE article_id = :id ORDER BY feed_id")
                .param("id", articleId).query(Long.class).list();
    }

    private List<String> guidsOf(long sourceId) {
        return jdbcClient.sql("SELECT guid_key FROM article WHERE source_id = :id ORDER BY guid_key")
                .param("id", sourceId).query(String.class).list();
    }

    private long feedSource(long feedId) {
        return count("SELECT source_id FROM feed WHERE id = " + feedId);
    }

    @Test
    void anArticleOnlyTheMovingFeedLinksMovesAndALeftBehindSourceIsKept() {
        long exclusive = data.article(source, "exclusive", null, EARLY);
        long stays = data.article(source, "stays", null, EARLY);
        data.link(exclusive, moving, EARLY, "h1");
        data.link(stays, staying, EARLY, "h2");

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result).isEqualTo(new MoveResult(moving, source, target, 1, 0, 0, 0, false));
        assertThat(guidsOf(target)).containsExactly("exclusive");
        assertThat(guidsOf(source)).containsExactly("stays");
        assertThat(feedSource(moving)).isEqualTo(target);
        assertThat(feedSource(staying)).isEqualTo(source);
        assertThat(linkedFeeds(exclusive)).containsExactly(moving);
    }

    @Test
    void anArticleSharedWithAStayingFeedIsCopiedAndTheOriginalKeepsTheOtherLinks() {
        long shared = data.article(source, "shared", "https://example.test/a/shared", EARLY);
        data.link(shared, moving, EARLY, "h-moving");
        data.link(shared, staying, LATE, "h-staying");

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result).isEqualTo(new MoveResult(moving, source, target, 0, 1, 0, 0, false));
        assertThat(linkedFeeds(shared)).containsExactly(staying);
        assertThat(guidsOf(source)).containsExactly("shared");
        assertThat(guidsOf(target)).containsExactly("shared");
        long copy = count("SELECT id FROM article WHERE source_id = " + target);
        assertThat(copy).isNotEqualTo(shared);
        assertThat(linkedFeeds(copy)).containsExactly(moving);
        assertThat(count("SELECT count(*) FROM article_feed WHERE article_id = " + copy
                + " AND content_hash = 'h-moving' AND first_seen_at = '2026-10-01T08:00:00Z'")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM article o JOIN article c ON c.source_id = %d AND c.guid_key = o.guid_key
                WHERE o.source_id = %d AND c.id <> o.id AND c.raw_guid = o.raw_guid AND c.link = o.link
                  AND c.link_key = o.link_key AND c.content_hash = o.content_hash AND c.title = o.title
                  AND c.excerpt = o.excerpt AND c.author = o.author AND c.categories = o.categories
                  AND c.effective_at = o.effective_at AND c.fetched_at = o.fetched_at
                  AND c.modified_at = o.modified_at
                """.formatted(target, source))).isEqualTo(1);
    }

    @Test
    void aTargetArticleWithTheSameGuidAbsorbsOnlyTheMovingFeedsLink() {
        long original = data.article(source, "same", null, LATE);
        long survivor = data.article(target, "same", null, EARLY);
        data.link(original, moving, EARLY, "h-moving");
        data.link(original, staying, LATE, "h-staying");
        data.link(survivor, targetFeed, LATE, "h-target");

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result).isEqualTo(new MoveResult(moving, source, target, 0, 0, 1, 1, false));
        assertThat(linkedFeeds(survivor)).containsExactly(moving, targetFeed);
        assertThat(linkedFeeds(original)).containsExactly(staying);
        assertThat(guidsOf(target)).containsExactly("same");
    }

    @Test
    void aTargetMatchThatNoOtherFeedLinksIsDeletedFromTheSource() {
        long original = data.article(source, "same", null, EARLY);
        long survivor = data.article(target, "same", null, LATE);
        data.link(original, moving, EARLY, "h");

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result.articlesCollapsed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM article WHERE id = " + original)).isZero();
        assertThat(linkedFeeds(survivor)).containsExactly(moving);
    }

    @Test
    void aMatchByLinkAbsorbsTheLinkEvenWhenTheSourceArticleIsOlder() {
        long original = data.article(source, "guid-a", "https://example.test/a/story", EARLY);
        long survivor = data.article(target, "guid-b", "https://example.test/a/story", LATE);
        data.link(original, moving, EARLY, "h");

        merger.moveFeed(moving, target);

        assertThat(linkedFeeds(survivor)).containsExactly(moving);
        assertThat(guidsOf(target)).containsExactly("guid-b");
    }

    @Test
    void aSourceLeftWithNoFeedsAndNoArticlesIsDeleted() {
        jdbcClient.sql("DELETE FROM feed WHERE id = :id").param("id", staying).update();
        long article = data.article(source, "only", null, EARLY);
        data.link(article, moving, EARLY, "h");

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result.sourceDeleted()).isTrue();
        assertThat(count("SELECT count(*) FROM source WHERE id = " + source)).isZero();
        assertThat(guidsOf(target)).containsExactly("only");
    }

    @Test
    void aSourceThatStillHasAnUnlinkedArticleIsKept() {
        jdbcClient.sql("DELETE FROM feed WHERE id = :id").param("id", staying).update();
        data.article(source, "orphan", null, EARLY);

        MoveResult result = merger.moveFeed(moving, target);

        assertThat(result.sourceDeleted()).isFalse();
        assertThat(count("SELECT count(*) FROM source WHERE id = " + source)).isEqualTo(1);
    }

    @Test
    void movingToTheCurrentSourceChangesNothingAndTakesNoLock() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try (HeldLock held = HeldLock.on(tx, sourceLock, source)) {
            MoveResult result = CompletableFuture.supplyAsync(() -> merger.moveFeed(moving, source))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(result).isEqualTo(new MoveResult(moving, source, source, 0, 0, 0, 0, false));
            assertThat(held).isNotNull();
        }
        assertThat(feedSource(moving)).isEqualTo(source);
    }

    @Test
    void anUnknownFeedOrTargetIs404() {
        assertThatThrownBy(() -> merger.moveFeed(moving + 100, target))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        assertThatThrownBy(() -> merger.moveFeed(moving, target + 100))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Source " + (target + 100) + " does not exist.");
                });
        assertThat(feedSource(moving)).isEqualTo(source);
    }

    @Test
    void aFeedMovedToTheTargetWhileWaitingForTheLocksIsAlreadyDone() throws Exception {
        MoveResult result = moveWhileFeedIsReassigned(target);

        assertThat(result).isEqualTo(new MoveResult(moving, target, target, 0, 0, 0, 0, false));
    }

    @Test
    void aFeedMovedToAThirdSourceWhileWaitingForTheLocksFailsRetryably() {
        long third = data.source("third.example", null);

        assertThatThrownBy(() -> moveWhileFeedIsReassigned(third))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(FeedSourceChangedException.class);
        assertThat(feedSource(moving)).isEqualTo(third);
    }

    @Test
    void theMoveLogsOneInfoAuditLineWithIdsAndCounts() {
        long article = data.article(source, "g", null, EARLY);
        data.link(article, moving, EARLY, "h");

        try (LogCapture logs = LogCapture.start()) {
            merger.moveFeed(moving, target);

            assertThat(logs.at(Level.INFO, SourceMerger.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.FEED_ID, moving)
                        .containsEntry(LogKeys.SOURCE_ID, source)
                        .containsEntry(LogKeys.TARGET_SOURCE_ID, target)
                        .containsEntry(LogKeys.ARTICLES_MOVED, 1)
                        .containsEntry(LogKeys.ARTICLES_COPIED, 0)
                        .containsEntry(LogKeys.ARTICLES_COLLAPSED, 0)
                        .containsEntry(LogKeys.LINKS_FOLDED, 0)
                        .containsEntry(LogKeys.SOURCE_DELETED, false);
                assertThat(event.getFormattedMessage()).isEqualTo("Feed " + moving + " moved from source " + source
                        + " (bbci.co.uk) to " + target + " (bbc.co.uk): 1 articles moved, 0 copied, 0 collapsed");
            });
        }
    }

    /** Starts the move, which blocks on the locks, reassigns the feed while they are held elsewhere, and returns it. */
    private MoveResult moveWhileFeedIsReassigned(long reassignedTo) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CompletableFuture<MoveResult> move = tx.execute(status -> {
            sourceLock.acquire(source);
            CompletableFuture<MoveResult> pending = CompletableFuture.supplyAsync(() -> merger.moveFeed(moving, target));
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(
                    () -> count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0);
            jdbcClient.sql("UPDATE feed SET source_id = :id WHERE id = :feed")
                    .param("id", reassignedTo).param("feed", moving).update();
            return pending;
        });
        return move.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
