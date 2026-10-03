package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.identity.FeedRedirectApplier.RedirectOutcome;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.LogCapture;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedRedirectApplierIT extends AbstractIntegrationTest {

    private static final String OLD = "http://old.test/feed";
    private static final String NEW = "https://new.test/feed";
    private static final String SOURCE_KEY = "old.test";

    @Autowired
    private FeedRedirectApplier applier;

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    private long insert(String url) {
        long sourceId = sources.findOrCreate(SOURCE_KEY, null).getId();
        return inserter.insert(new NewFeed(sourceId, "F", url, null, null, Topic.TECH, null)).orElseThrow();
    }

    private String urlOf(long id) {
        return jdbcClient.sql("SELECT url FROM feed WHERE id = :id").param("id", id).query(String.class).single();
    }

    private double redirects(String outcome) {
        return meters().counter("argus.feed.redirect", "source", SOURCE_KEY, "outcome", outcome);
    }

    @Test
    void aTargetEqualToTheStoredUrlChangesNothing() {
        long id = insert(OLD);

        RedirectOutcome outcome = applier.apply(id, SOURCE_KEY, OLD, URI.create("HTTP://OLD.test/feed#x"));

        assertThat(outcome).isEqualTo(new RedirectOutcome.NoChange());
        assertThat(urlOf(id)).isEqualTo(OLD);
    }

    @Test
    void aTargetThatIsNotAnHttpUrlChangesNothing() {
        long id = insert(OLD);

        assertThat(applier.apply(id, SOURCE_KEY, OLD, URI.create("ftp://new.test/feed")))
                .isEqualTo(new RedirectOutcome.NoChange());
    }

    @Test
    void aFreeTargetIsAppliedCountedAndLoggedWithRedactedUrls() {
        long id = insert(OLD);
        double before = redirects("permanent_applied");

        try (LogCapture logs = LogCapture.start()) {
            RedirectOutcome outcome = applier.apply(id, SOURCE_KEY, OLD, URI.create(NEW + "?token=SECRET"));

            assertThat(outcome).isEqualTo(new RedirectOutcome.Applied(NEW + "?token=SECRET"));
            assertThat(logs.at(Level.INFO, FeedRedirectApplier.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("feedId", id)
                        .containsEntry("sourceKey", SOURCE_KEY)
                        .containsEntry("url", OLD)
                        .containsEntry("newUrl", NEW);
                assertThat(event.getFormattedMessage()).contains("Feed " + id).contains(NEW);
            });
            logs.assertNothingLogged("SECRET");
        }
        assertThat(urlOf(id)).isEqualTo(NEW + "?token=SECRET");
        assertThat(redirects("permanent_applied")).isEqualTo(before + 1);
    }

    @Test
    void anHttpToHttpsUpgradeWithAnEqualFoldIsApplied() {
        long id = insert(OLD);

        RedirectOutcome outcome = applier.apply(id, SOURCE_KEY, OLD, URI.create("https://old.test/feed"));

        assertThat(outcome).isEqualTo(new RedirectOutcome.Applied("https://old.test/feed"));
        assertThat(urlOf(id)).isEqualTo("https://old.test/feed");
    }

    @Test
    void aTargetHeldByAnotherFeedDisablesTheFeedWithoutCountingAFailure() {
        long holder = insert("https://www.new.test/feed/?t=SECRET");
        long id = insert(OLD);
        double conflictsBefore = redirects("permanent_conflict");
        double appliedBefore = redirects("permanent_applied");

        try (LogCapture logs = LogCapture.start()) {
            RedirectOutcome outcome = applier.apply(id, SOURCE_KEY, OLD, URI.create(NEW + "?t=SECRET"));

            assertThat(outcome).isEqualTo(new RedirectOutcome.Conflict(holder));
            assertThat(logs.at(Level.WARN, FeedRedirectApplier.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("feedId", id)
                        .containsEntry("existingFeedId", holder)
                        .containsEntry("sourceKey", SOURCE_KEY)
                        .containsEntry("reason", "duplicate_feed")
                        .containsEntry("url", OLD)
                        .containsEntry("newUrl", NEW);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Feed " + id + " disabled: its permanent redirect to " + NEW
                                + " duplicates feed " + holder);
            });
            logs.assertNothingLogged("SECRET");
        }
        assertThat(jdbcClient.sql("SELECT enabled, last_error, consecutive_failures, url FROM feed WHERE id = :id")
                .param("id", id).query().singleRow())
                .containsEntry("enabled", false)
                .containsEntry("last_error", "duplicate of feed " + holder)
                .containsEntry("consecutive_failures", 0)
                .containsEntry("url", OLD);
        assertThat(redirects("permanent_conflict")).isEqualTo(conflictsBefore + 1);
        assertThat(redirects("permanent_applied")).isEqualTo(appliedBefore);
    }

    @Test
    void aTargetMatchingAnotherFeedsSelfUrlIsAConflictToo() {
        long holder = insert("https://holder.test/f");
        jdbcClient.sql("UPDATE feed SET self_url = :s WHERE id = :id")
                .param("s", NEW).param("id", holder).update();
        long id = insert(OLD);

        assertThat(applier.apply(id, SOURCE_KEY, OLD, URI.create(NEW)))
                .isEqualTo(new RedirectOutcome.Conflict(holder));
    }

    @Test
    void aFeedThatMovedMeanwhileOrIsGoneIsLeftAlone() {
        long id = insert("https://moved.test/feed");

        assertThat(applier.apply(id, SOURCE_KEY, OLD, URI.create(NEW))).isEqualTo(new RedirectOutcome.NoChange());
        assertThat(applier.apply(id + 99, SOURCE_KEY, OLD, URI.create(NEW)))
                .isEqualTo(new RedirectOutcome.NoChange());
        assertThat(urlOf(id)).isEqualTo("https://moved.test/feed");
    }
}
