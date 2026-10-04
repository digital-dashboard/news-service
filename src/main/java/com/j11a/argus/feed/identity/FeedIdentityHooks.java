package com.j11a.argus.feed.identity;

import com.j11a.argus.feed.identity.FeedRedirectApplier.RedirectOutcome;
import com.j11a.argus.observability.LogKeys;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** What a poll does about a feed's identity: follow a permanent redirect, and remember the self link. */
@Slf4j
@Component
public class FeedIdentityHooks {

    private final FeedRedirectApplier redirectApplier;
    private final FeedIdentityRegistry registry;

    public FeedIdentityHooks(FeedRedirectApplier redirectApplier, FeedIdentityRegistry registry) {
        this.redirectApplier = redirectApplier;
        this.registry = registry;
    }

    public RedirectOutcome applyRedirect(long feedId, String sourceKey, String storedUrl, URI permanentTarget) {
        return redirectApplier.apply(feedId, sourceKey, storedUrl, permanentTarget);
    }

    /** Best effort: the self link is only a bonus identity, so no failure here may fail the ingest. */
    public void recordSelfUrl(long feedId, @Nullable String selfLink) {
        try {
            registry.recordSelfUrl(feedId, selfLink);
        } catch (RuntimeException e) {
            log.atWarn()
                    .setMessage("Feed " + feedId + " could not record its self link")
                    .addKeyValue(LogKeys.FEED_ID, feedId)
                    .setCause(e)
                    .log();
        }
    }
}
