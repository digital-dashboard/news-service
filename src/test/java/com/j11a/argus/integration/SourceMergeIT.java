package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceMergeResponse;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SourceMergeIT extends AbstractIntegrationTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-01T10:00:00Z");
    private static final String STORY = "https://example.test/a/story";
    private static final String SOURCES = "/news/v2/sources";
    private static final int LOCK_NAMESPACE = 4100;
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
    private long sourceFeed;
    private long targetFeed;

    @BeforeEach
    void seed() {
        data = new MergeData(jdbcClient);
        source = data.source("bbci.co.uk", "https://www.bbci.co.uk");
        target = data.source("bbc.co.uk", "https://www.bbc.co.uk/news");
        sourceFeed = data.feed(source, "https://bbci.co.uk/feed");
        targetFeed = data.feed(target, "https://bbc.co.uk/feed");
    }

    private ResultActions postMerge(long id, String body) throws Exception {
        return mockMvc.perform(post(SOURCES + "/" + id + "/merge")
                .header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<String> guidsOf(long sourceId) {
        return jdbcClient.sql("SELECT guid_key FROM article WHERE source_id = :id ORDER BY guid_key")
                .param("id", sourceId).query(String.class).list();
    }

    private List<Long> linkedFeeds(long articleId) {
        return jdbcClient.sql("SELECT feed_id FROM article_feed WHERE article_id = :id ORDER BY feed_id")
                .param("id", articleId).query(Long.class).list();
    }

    @Test
    void overlapByGuidCollapsesOntoTheOlderArticleAndMovesTheRest() {
        long sourceDuplicate = data.article(source, "g1", null, LATE);
        long sourceOnly = data.article(source, "g2", null, LATE);
        long targetSurvivor = data.article(target, "g1", null, EARLY);
        data.link(sourceDuplicate, sourceFeed, LATE, "h-source");
        data.link(sourceOnly, sourceFeed, LATE, "h2");
        data.link(targetSurvivor, targetFeed, EARLY, "h-target");

        SourceMergeResponse response = merger.merge(source, target);

        assertThat(response).isEqualTo(new SourceMergeResponse(source, target, 1, 1, 1, 1));
        assertThat(count("SELECT count(*) FROM source WHERE id = " + source)).isZero();
        assertThat(guidsOf(target)).containsExactly("g1", "g2");
        assertThat(linkedFeeds(targetSurvivor)).containsExactly(sourceFeed, targetFeed);
        assertThat(linkedFeeds(sourceOnly)).containsExactly(sourceFeed);
        assertThat(count("SELECT count(*) FROM article_feed")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM feed WHERE source_id = " + target)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + source)).isZero();
    }

    @Test
    void overlapByLinkKeepsTheOlderArticleEvenWhenItIsOnTheMergedSourceSide() {
        long sourceSurvivor = data.article(source, "guid-a", STORY, EARLY);
        long targetDuplicate = data.article(target, "guid-b", STORY, LATE);
        data.link(sourceSurvivor, sourceFeed, EARLY, "h1");
        data.link(targetDuplicate, targetFeed, LATE, "h2");

        SourceMergeResponse response = merger.merge(source, target);

        assertThat(response.articlesCollapsed()).isEqualTo(1);
        assertThat(response.articlesMoved()).isEqualTo(1);
        assertThat(guidsOf(target)).containsExactly("guid-a");
        assertThat(count("SELECT count(*) FROM article WHERE id = " + targetDuplicate)).isZero();
        assertThat(linkedFeeds(sourceSurvivor)).containsExactly(sourceFeed, targetFeed);
    }

    @Test
    void rootAndHomepageLinksAreNotCollapsedByLink() {
        data.article(source, "root-a", "https://example.test", EARLY);
        data.article(target, "root-b", "https://example.test", EARLY);
        data.article(source, "home-a", "https://bbc.co.uk/news", EARLY);
        data.article(target, "home-b", "https://bbc.co.uk/news", EARLY);

        SourceMergeResponse response = merger.merge(source, target);

        assertThat(response.articlesCollapsed()).isZero();
        assertThat(guidsOf(target)).containsExactly("home-a", "home-b", "root-a", "root-b");
    }

    @Test
    void aMergeWithNoOverlapMovesEverythingAndFoldsNothing() {
        data.article(source, "only-source", null, EARLY);

        SourceMergeResponse response = merger.merge(source, target);

        assertThat(response).isEqualTo(new SourceMergeResponse(source, target, 1, 1, 0, 0));
    }

    @Test
    void theMergeLogsOneInfoAuditLineWithIdsAndCounts() {
        long duplicate = data.article(source, "g1", null, LATE);
        long survivor = data.article(target, "g1", null, EARLY);
        data.link(duplicate, sourceFeed, LATE, "h");
        data.link(survivor, targetFeed, EARLY, "h");

        try (LogCapture logs = LogCapture.start()) {
            merger.merge(source, target);

            assertThat(logs.at(Level.INFO, SourceMerger.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.SOURCE_ID, source)
                        .containsEntry(LogKeys.SOURCE_KEY, "bbci.co.uk")
                        .containsEntry(LogKeys.TARGET_SOURCE_ID, target)
                        .containsEntry(LogKeys.FEEDS_MOVED, 1)
                        .containsEntry(LogKeys.ARTICLES_MOVED, 0)
                        .containsEntry(LogKeys.ARTICLES_COLLAPSED, 1)
                        .containsEntry(LogKeys.LINKS_FOLDED, 1);
                assertThat(event.getFormattedMessage()).isEqualTo("Source " + source + " (bbci.co.uk) merged into "
                        + target + " (bbc.co.uk): 1 feeds and 0 articles moved, 1 collapsed");
            });
        }
    }

    @Test
    void mergingASourceIntoItselfIs422BeforeAnyLockIsTaken() throws Exception {
        postMerge(source, "{\"targetSourceId\":" + source + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SOURCE_MERGE_INVALID"));
    }

    @Test
    void aMissingSourceOrTargetIs404NamingTheMissingId() throws Exception {
        long missing = target + 100;

        postMerge(missing, "{\"targetSourceId\":" + target + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Source " + missing + " does not exist."));
        postMerge(source, "{\"targetSourceId\":" + missing + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Source " + missing + " does not exist."));
        assertThat(count("SELECT count(*) FROM source")).isEqualTo(2);
    }

    @Test
    void thePostReturnsTheMergeCounts() throws Exception {
        data.article(source, "g1", null, EARLY);

        postMerge(source, "{\"targetSourceId\":" + target + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(source))
                .andExpect(jsonPath("$.targetSourceId").value(target))
                .andExpect(jsonPath("$.feedsMoved").value(1))
                .andExpect(jsonPath("$.articlesMoved").value(1))
                .andExpect(jsonPath("$.articlesCollapsed").value(0))
                .andExpect(jsonPath("$.linksFolded").value(0));
    }

    @Test
    void aFailureAfterTheFoldRollsEverythingBack() {
        long duplicate = data.article(source, "g1", null, LATE);
        long survivor = data.article(target, "g1", null, EARLY);
        data.link(duplicate, sourceFeed, LATE, "h-source");
        data.link(survivor, targetFeed, EARLY, "h-target");
        jdbcClient.sql("""
                CREATE FUNCTION fail_source_delete() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'forced failure'; END $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_source_delete BEFORE DELETE ON source
                FOR EACH ROW EXECUTE FUNCTION fail_source_delete()
                """).update();
        try {
            assertThatThrownBy(() -> merger.merge(source, target)).hasMessageContaining("forced failure");
        } finally {
            jdbcClient.sql("DROP TRIGGER fail_source_delete ON source").update();
            jdbcClient.sql("DROP FUNCTION fail_source_delete()").update();
        }

        assertThat(count("SELECT count(*) FROM source")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM article WHERE source_id = " + source)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM feed WHERE source_id = " + source)).isEqualTo(1);
        assertThat(linkedFeeds(duplicate)).containsExactly(sourceFeed);
        assertThat(linkedFeeds(survivor)).containsExactly(targetFeed);
    }

    @Test
    void oppositeConcurrentMergesLetOneWinAndTheOtherFindItsSourceGone() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        CompletableFuture<SourceMergeResponse> forward = CompletableFuture.supplyAsync(() -> mergeAfter(start, source, target));
        CompletableFuture<SourceMergeResponse> backward = CompletableFuture.supplyAsync(() -> mergeAfter(start, target, source));

        List<Object> outcomes = List.of(outcome(forward), outcome(backward));

        assertThat(outcomes).filteredOn(SourceMergeResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND));
        assertThat(count("SELECT count(*) FROM source")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM feed WHERE source_id = (SELECT id FROM source)")).isEqualTo(2);
    }

    @Test
    void theLowerSourceLockIsTakenFirstSoAWaiterHoldsNothingThatBlocksTheOtherDirection() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CompletableFuture<SourceMergeResponse> merge;
        // The higher id is merged into the lower one while the lower id's lock is held elsewhere.
        try (HeldLock held = HeldLock.on(tx, sourceLock, source)) {
            merge = CompletableFuture.supplyAsync(() -> merger.merge(target, source));
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(this::someAdvisoryLockIsWaiting);

            Boolean higherLockIsFree = tx.execute(status -> jdbcClient
                    .sql("SELECT pg_try_advisory_xact_lock(:ns, :id)")
                    .param("ns", LOCK_NAMESPACE).param("id", Math.toIntExact(target))
                    .query(Boolean.class).single());
            assertThat(higherLockIsFree).isTrue();
            assertThat(held).isNotNull();
        }

        assertThat(merge.get(WAIT_SECONDS, TimeUnit.SECONDS).sourceId()).isEqualTo(target);
    }

    @Test
    void aMergeWaitsForAnIngestWriterHoldingTheSourceLockAndThenIncludesItsArticle() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CompletableFuture<SourceMergeResponse> merge = tx.execute(status -> {
            sourceLock.acquire(source);
            CompletableFuture<SourceMergeResponse> pending =
                    CompletableFuture.supplyAsync(() -> merger.merge(source, target));
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(this::someAdvisoryLockIsWaiting);
            data.article(source, "written-during-merge", null, LATE);
            return pending;
        });

        SourceMergeResponse response = merge.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(response.articlesMoved()).isEqualTo(1);
        assertThat(guidsOf(target)).containsExactly("written-during-merge");
    }

    private SourceMergeResponse mergeAfter(CyclicBarrier start, long from, long into) {
        try {
            start.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return merger.merge(from, into);
    }

    private static Object outcome(CompletableFuture<SourceMergeResponse> future) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private boolean someAdvisoryLockIsWaiting() {
        return count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0;
    }
}
