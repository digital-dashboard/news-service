package com.j11a.argus.feed.identity;

import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.StoredUrls;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Moves a feed to the target of a permanent redirect, or disables it when that target is another feed. */
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
    }

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
        switch (outcome) {
            case RedirectOutcome.Applied applied -> reportApplied(feedId, sourceKey, storedUrl, applied.newUrl());
            case RedirectOutcome.Conflict conflict ->
                    reportConflict(feedId, sourceKey, storedUrl, target, conflict.existingFeedId());
            case RedirectOutcome.NoChange ignored -> { }
        }
        return outcome;
    }

    private RedirectOutcome changeUrl(long feedId, String storedUrl, String target) {
        try {
            return registry.withIdentityLock(() -> changeUrlLocked(feedId, storedUrl, target));
        } catch (DuplicateKeyException e) {
            // The failed statement aborted that transaction, so the holder is looked up outside it.
            return registry.findExactHolder(target, feedId)
                    .<RedirectOutcome>map(RedirectOutcome.Conflict::new)
                    .orElseGet(RedirectOutcome.NoChange::new);
        }
    }

    private RedirectOutcome changeUrlLocked(long feedId, String storedUrl, String target) {
        var candidate = new FeedIdentityRegistry.Candidate(IdentityKind.REDIRECT, target);
        var conflict = registry.findConflict(List.of(candidate), feedId);
        if (conflict.isPresent()) {
            return new RedirectOutcome.Conflict(conflict.get().existingFeedId());
        }
        int updated = jdbc.sql("UPDATE feed SET url = :new, updated_at = :now WHERE id = :id AND url = :old")
                .param("new", target)
                .param("old", storedUrl)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", feedId)
                .update();
        return updated == 0 ? new RedirectOutcome.NoChange() : new RedirectOutcome.Applied(target);
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

    private void reportConflict(long feedId, String sourceKey, String storedUrl, String target, long existingFeedId) {
        telemetry.redirect(sourceKey, FeedIdentityTelemetry.PERMANENT_CONFLICT);
        healthUpdater.recordDuplicate(feedId, existingFeedId, clock.instant());
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
    }
}
