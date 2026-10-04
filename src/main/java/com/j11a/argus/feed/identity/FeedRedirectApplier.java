package com.j11a.argus.feed.identity;

import com.j11a.argus.config.Clocks;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.StoredUrls;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Moves a feed to the target of a permanent redirect. A target that is another feed's url disables the feed; one that
 * only another feed claims as its self link leaves the feed as it is.
 */
@Slf4j
@Component
public class FeedRedirectApplier {

    public sealed interface RedirectOutcome {

        record NoChange() implements RedirectOutcome {
        }

        record Applied(String newUrl) implements RedirectOutcome {
        }

        record Conflict(long existingFeedId) implements RedirectOutcome {
        }

        record Skipped(long existingFeedId) implements RedirectOutcome {
        }
    }

    private static final String SELF_LINK_CLAIMED = "self_link_claimed";
    private static final String URL_OF_FEED = "SELECT url FROM feed WHERE id = :id";
    private static final String UPDATE_URL = """
            UPDATE feed SET url = :new, updated_at = :now WHERE id = :id AND url = :old
            """;

    private final JdbcClient jdbc;
    private final FeedIdentityRegistry registry;
    private final FeedIdentityTelemetry telemetry;
    private final FeedHealthUpdater healthUpdater;
    private final Clock clock;

    public FeedRedirectApplier(JdbcClient jdbc, FeedIdentityRegistry registry, FeedIdentityTelemetry telemetry,
            FeedHealthUpdater healthUpdater, Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.telemetry = telemetry;
        this.healthUpdater = healthUpdater;
        this.clock = clock;
    }

    /** Not transactional itself: the check and the update share one short transaction inside the registry. */
    public RedirectOutcome apply(long feedId, String sourceKey, String storedUrl, URI permanentTarget) {
        String target = StoredUrls.clean(permanentTarget.toString());
        if (target == null || target.equals(storedUrl)) {
            return new RedirectOutcome.NoChange();
        }
        RedirectOutcome outcome = changeUrl(feedId, storedUrl, target);
        return switch (outcome) {
            case RedirectOutcome.Applied applied -> {
                reportApplied(feedId, sourceKey, storedUrl, applied.newUrl());
                yield outcome;
            }
            case RedirectOutcome.Conflict conflict ->
                    disable(feedId, sourceKey, storedUrl, target, conflict.existingFeedId());
            case RedirectOutcome.Skipped skipped -> {
                reportSkipped(feedId, sourceKey, storedUrl, target, skipped.existingFeedId());
                yield outcome;
            }
            case RedirectOutcome.NoChange ignored -> outcome;
        };
    }

    private RedirectOutcome changeUrl(long feedId, String storedUrl, String target) {
        try {
            return registry.withIdentityLock(() -> changeUrlLocked(feedId, storedUrl, target));
        } catch (DuplicateKeyException e) {
            // The failed statement aborted that transaction, so the holder is looked up outside it.
            return holderOutcome(feedId, target).orElseGet(RedirectOutcome.NoChange::new);
        }
    }

    private RedirectOutcome changeUrlLocked(long feedId, String storedUrl, String target) {
        // Only the lock makes this read binding: a PATCH may have changed the URL since the poll loaded the feed.
        Optional<String> current = jdbc.sql(URL_OF_FEED).param("id", feedId).query(String.class).optional();
        if (current.isEmpty() || !current.get().equals(storedUrl)) {
            return new RedirectOutcome.NoChange();
        }
        Optional<RedirectOutcome> held = holderOutcome(feedId, target);
        if (held.isPresent()) {
            return held.get();
        }
        int updated = jdbc.sql(UPDATE_URL)
                .param("new", target)
                .param("old", storedUrl)
                .param("now", Clocks.utcNow(clock))
                .param("id", feedId)
                .update();
        return updated == 0 ? new RedirectOutcome.NoChange() : new RedirectOutcome.Applied(target);
    }

    private Optional<RedirectOutcome> holderOutcome(long feedId, String target) {
        var candidate = new FeedIdentityRegistry.Candidate(IdentityKind.REDIRECT, target);
        return registry.findConflict(List.of(candidate), feedId).map(conflict -> conflict.heldBySelfLink()
                ? new RedirectOutcome.Skipped(conflict.existingFeedId())
                : new RedirectOutcome.Conflict(conflict.existingFeedId()));
    }

    private void reportApplied(long feedId, String sourceKey, String storedUrl, String newUrl) {
        telemetry.redirect(sourceKey, FeedIdentityTelemetry.PERMANENT_APPLIED);
        String oldRedacted = HttpUrls.redact(storedUrl);
        String newRedacted = HttpUrls.redact(newUrl);
        log.atInfo()
                .setMessage("Feed " + feedId + " moved to " + newRedacted + " after a permanent redirect from "
                        + oldRedacted)
                .addKeyValue(LogKeys.FEED_ID, feedId)
                .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                .addKeyValue(LogKeys.URL, oldRedacted)
                .addKeyValue(LogKeys.NEW_URL, newRedacted)
                .log();
    }

    /** Disables the feed unless its URL changed since the poll loaded it; then nothing is counted or logged. */
    private RedirectOutcome disable(long feedId, String sourceKey, String storedUrl, String target,
            long existingFeedId) {
        if (!healthUpdater.recordDuplicate(feedId, storedUrl, existingFeedId, clock.instant())) {
            return new RedirectOutcome.NoChange();
        }
        telemetry.redirect(sourceKey, FeedIdentityTelemetry.PERMANENT_CONFLICT);
        String targetRedacted = HttpUrls.redact(target);
        log.atWarn()
                .setMessage("Feed " + feedId + " disabled: its permanent redirect to " + targetRedacted
                        + " duplicates feed " + existingFeedId)
                .addKeyValue(LogKeys.FEED_ID, feedId)
                .addKeyValue(LogKeys.EXISTING_FEED_ID, existingFeedId)
                .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                .addKeyValue(LogKeys.URL, HttpUrls.redact(storedUrl))
                .addKeyValue(LogKeys.NEW_URL, targetRedacted)
                .addKeyValue(LogKeys.REASON, FailureReasons.DUPLICATE_FEED)
                .log();
        return new RedirectOutcome.Conflict(existingFeedId);
    }

    private void reportSkipped(long feedId, String sourceKey, String storedUrl, String target, long existingFeedId) {
        telemetry.redirect(sourceKey, FeedIdentityTelemetry.PERMANENT_SKIPPED);
        String targetRedacted = HttpUrls.redact(target);
        log.atWarn()
                .setMessage("Feed " + feedId + " keeps its URL: its permanent redirect to " + targetRedacted
                        + " is claimed as a self link by feed " + existingFeedId)
                .addKeyValue(LogKeys.FEED_ID, feedId)
                .addKeyValue(LogKeys.EXISTING_FEED_ID, existingFeedId)
                .addKeyValue(LogKeys.SOURCE_KEY, sourceKey)
                .addKeyValue(LogKeys.URL, HttpUrls.redact(storedUrl))
                .addKeyValue(LogKeys.NEW_URL, targetRedacted)
                .addKeyValue(LogKeys.REASON, SELF_LINK_CLAIMED)
                .log();
    }
}
