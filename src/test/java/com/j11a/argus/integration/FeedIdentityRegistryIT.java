package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.identity.FeedIdentityLock;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedIdentityRegistry.Candidate;
import com.j11a.argus.feed.identity.FeedIdentityRegistry.Conflict;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.LogCapture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

class FeedIdentityRegistryIT extends AbstractIntegrationTest {

    private static final String URL = "https://www.example.test/rss/";
    private static final String SELF = "https://selfhost.test/atom.xml";

    @Autowired
    private FeedIdentityRegistry registry;

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    @Autowired
    private FeedIdentityLock identityLock;

    @Test
    void theIdentityLockCannotBeTakenOutsideATransaction() {
        assertThatThrownBy(identityLock::acquire).isInstanceOf(IllegalTransactionStateException.class);
    }

    private long sourceId() {
        return sources.findOrCreate("example.test", "https://example.test/").getId();
    }

    private long insert(String url, String selfUrl) {
        return inserter.insert(new NewFeed(sourceId(), "F", url, null, selfUrl, Topic.TECH, null)).orElseThrow();
    }

    private Optional<Conflict> check(IdentityKind kind, String url, Long exclude) {
        return registry.findConflict(List.of(new Candidate(kind, url)), exclude);
    }

    @Test
    void anEnteredUrlThatFoldsToAnExistingUrlConflictsAndReportsItsKind() {
        long existing = insert(URL, null);

        assertThat(check(IdentityKind.ENTERED, "http://example.test/rss", null))
                .contains(new Conflict(IdentityKind.ENTERED, existing, false));
    }

    @Test
    void aRedirectTargetThatMatchesAnExistingUrlConflictsAsRedirect() {
        long existing = insert(URL, null);

        assertThat(check(IdentityKind.REDIRECT, "https://example.test/rss", null))
                .contains(new Conflict(IdentityKind.REDIRECT, existing, false));
    }

    @Test
    void aSelfLinkThatMatchesAnExistingUrlConflictsAsSelfLink() {
        long existing = insert(URL, null);

        assertThat(check(IdentityKind.SELF_LINK, "https://example.test/rss/", null))
                .contains(new Conflict(IdentityKind.SELF_LINK, existing, false));
    }

    @Test
    void aCandidateThatMatchesAnotherFeedsSelfUrlConflictsAcrossUrlAndSelfUrl() {
        long existing = insert(URL, SELF);

        assertThat(check(IdentityKind.ENTERED, "http://www.selfhost.test/atom.xml/", null))
                .contains(new Conflict(IdentityKind.ENTERED, existing, true));
    }

    @Test
    void aFeedWhoseUrlMatchesWinsOverAFeedWhoseSelfUrlOnlyMatches() {
        insert("https://claims-it-as-self.test/f", "https://claimed.test/f");
        long urlHolder = insert("https://claimed.test/f/", null);

        assertThat(check(IdentityKind.REDIRECT, "http://claimed.test/f", null))
                .contains(new Conflict(IdentityKind.REDIRECT, urlHolder, false));
    }

    @Test
    void ofSeveralFeedsHoldingTheSameIdentityTheLowestIdIsReported() {
        long lowest = insert("http://www.same.test/f", null);
        insert("https://same.test/f/", null);

        assertThat(check(IdentityKind.ENTERED, "https://same.test/f", null))
                .contains(new Conflict(IdentityKind.ENTERED, lowest, false));
    }

    @Test
    void aCandidateThatMatchesNothingHasNoConflictAndAQueryDifferenceIsNotAMatch() {
        insert("https://example.test/rss?id=1", null);

        assertThat(check(IdentityKind.ENTERED, "https://example.test/rss?id=2", null)).isEmpty();
    }

    @Test
    void theFeedBeingEditedIsExcludedFromItsOwnCheck() {
        long own = insert(URL, SELF);

        assertThat(check(IdentityKind.ENTERED, URL, own)).isEmpty();
        assertThat(check(IdentityKind.ENTERED, URL, own + 1)).isPresent();
    }

    @Test
    void theFirstCandidateInListOrderThatConflictsIsReported() {
        long first = insert("https://one.test/f", null);
        insert("https://two.test/f", null);

        Optional<Conflict> conflict = registry.findConflict(List.of(
                new Candidate(IdentityKind.ENTERED, "https://free.test/f"),
                new Candidate(IdentityKind.REDIRECT, "https://one.test/f"),
                new Candidate(IdentityKind.SELF_LINK, "https://two.test/f")), null);

        assertThat(conflict).contains(new Conflict(IdentityKind.REDIRECT, first, false));
    }

    @Test
    void twoConcurrentFoldEqualCheckThenInsertsUnderTheLockLetExactlyOneWin() throws Exception {
        long sourceId = sourceId();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<CompletableFuture<Void>> racers = List.of("https://race.test/feed", "http://www.race.test/feed/").stream()
                .map(url -> CompletableFuture.runAsync(() -> {
                    awaitQuietly(start);
                    registry.withIdentityLock(() -> {
                        if (registry.findConflict(List.of(new Candidate(IdentityKind.ENTERED, url)), null).isEmpty()) {
                            pause();
                            jdbcClient.sql("""
                                    INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
                                    VALUES (:s, 'R', :u, 'TECH', true, now(), now())
                                    """).param("s", sourceId).param("u", url).update();
                            winners.incrementAndGet();
                        }
                        return null;
                    });
                })).toList();

        start.countDown();
        for (CompletableFuture<Void> racer : racers) {
            racer.get(30, TimeUnit.SECONDS);
        }

        assertThat(winners).hasValue(1);
        assertThat(count("SELECT count(*) FROM feed")).isEqualTo(1);
    }

    @Test
    void aNewSelfUrlIsRecordedWithoutLoggingAConflict() {
        long id = insert(URL, null);

        try (LogCapture logs = LogCapture.start()) {
            registry.recordSelfUrl(id, SELF + "#frag");

            assertThat(logs.at(Level.INFO, FeedIdentityRegistry.class)).isEmpty();
        }
        assertThat(selfUrlOf(id)).isEqualTo(SELF);
    }

    @Test
    void aNullOrUnchangedSelfLinkLeavesTheRowAlone() {
        long id = insert(URL, SELF);
        Object before = jdbcClient.sql("SELECT updated_at FROM feed WHERE id = :id").param("id", id)
                .query().singleRow().get("updated_at");

        registry.recordSelfUrl(id, null);
        registry.recordSelfUrl(id, SELF);
        registry.recordSelfUrl(id, "not a url");

        assertThat(selfUrlOf(id)).isEqualTo(SELF);
        assertThat(jdbcClient.sql("SELECT updated_at FROM feed WHERE id = :id").param("id", id)
                .query().singleRow()).containsEntry("updated_at", before);
    }

    @Test
    void aSelfLinkThatConflictsWithAnotherFeedIsLeftOutAndLoggedAtInfoWithoutTheQuery() {
        long holder = insert("https://holder.test/feed?token=SECRET", null);
        long id = insert(URL, null);

        try (LogCapture logs = LogCapture.start()) {
            registry.recordSelfUrl(id, "https://www.holder.test/feed/?token=SECRET");

            assertThat(logs.at(Level.INFO, FeedIdentityRegistry.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("feedId", id)
                        .containsEntry("existingFeedId", holder)
                        .containsEntry("url", "https://www.holder.test/feed/");
            });
            logs.assertNothingLogged("SECRET");
        }
        assertThat(selfUrlOf(id)).isNull();
    }

    private String selfUrlOf(long id) {
        return jdbcClient.sql("SELECT self_url FROM feed WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getString(1)).list().getFirst();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
