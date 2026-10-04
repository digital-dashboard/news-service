package com.j11a.argus.feed.api;

import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedIdentityRegistry.Candidate;
import com.j11a.argus.feed.identity.FeedIdentityTelemetry;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** The identity checks that create and a URL change share: the three candidates and the 409 they turn into. */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FeedUrlChecks {

    private final FeedIdentityRegistry registry;
    private final FeedIdentityTelemetry telemetry;

    FeedUrlChecks(FeedIdentityRegistry registry, FeedIdentityTelemetry telemetry) {
        this.registry = registry;
        this.telemetry = telemetry;
    }

    static Candidate entered(String cleanedUrl) {
        return new Candidate(IdentityKind.ENTERED, cleanedUrl);
    }

    /** Entered, then where the download ended, then the feed's own self link; absent or repeated ones are left out. */
    static List<Candidate> candidates(String enteredUrl, URI finalUrl, @Nullable String selfLink) {
        List<Candidate> candidates = new ArrayList<>(List.of(entered(enteredUrl)));
        Set<String> seen = new HashSet<>(Set.of(enteredUrl));
        addIfNew(candidates, seen, IdentityKind.REDIRECT, StoredUrls.clean(finalUrl.toString()));
        addIfNew(candidates, seen, IdentityKind.SELF_LINK, StoredUrls.clean(selfLink));
        return List.copyOf(candidates);
    }

    private static void addIfNew(List<Candidate> candidates, Set<String> seen, IdentityKind kind,
            @Nullable String cleanedUrl) {
        if (cleanedUrl != null && seen.add(cleanedUrl)) {
            candidates.add(new Candidate(kind, cleanedUrl));
        }
    }

    /** Throws the 409 for the first candidate that names another feed. Hold the identity lock for a binding answer. */
    void requireFree(String action, List<Candidate> candidates, @Nullable Long excludeFeedId) {
        registry.findConflict(candidates, excludeFeedId).ifPresent(conflict -> {
            throw conflict(action, candidates.getFirst().cleanedUrl(), conflict.kind(), conflict.existingFeedId());
        });
    }

    ApiException conflict(String action, String enteredUrl, IdentityKind kind, @Nullable Long existingFeedId) {
        telemetry.conflict(kind);
        String redacted = HttpUrls.redact(enteredUrl);
        LoggingEventBuilder event = log.atInfo()
                .setMessage("Feed " + action + " conflicts with an existing feed for " + redacted
                        + " (" + kind.tag() + ")")
                .addKeyValue(LogKeys.URL, redacted)
                .addKeyValue(LogKeys.KIND, kind.tag());
        LogFields.put(event, LogKeys.EXISTING_FEED_ID, existingFeedId).log();
        Map<String, Object> properties = existingFeedId == null
                ? Map.of("kind", kind.tag())
                : Map.of("existingFeedId", existingFeedId, "kind", kind.tag());
        return new ApiException(ErrorCode.FEED_URL_CONFLICT, "A feed with this URL already exists.", properties);
    }
}
