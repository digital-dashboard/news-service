package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.SourceSummary;
import com.j11a.argus.url.HttpUrls;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record FeedResponse(
        long id,
        String name,
        String url,
        @Nullable String siteUrl,
        Topic topic,
        boolean enabled,
        SourceSummary source,
        Instant createdAt,
        @Nullable Instant lastFetchedAt,
        @Nullable Instant lastSuccessAt,
        @Nullable String lastError,
        int consecutiveFailures,
        String state) {

    public FeedResponse(
            long id,
            String name,
            String url,
            @Nullable String siteUrl,
            Topic topic,
            boolean enabled,
            SourceSummary source,
            Instant createdAt) {
        this(id, name, url, siteUrl, topic, enabled, source, createdAt, null, null, null, 0, "healthy");
    }

    public static final int DEFAULT_FAILING_THRESHOLD = 3;

    /** A feed URL can carry a token, so anyone but an admin sees it without user-info and query string. */
    public static FeedResponse of(Feed feed, boolean admin, int failingThreshold) {
        String url = admin ? feed.getUrl() : HttpUrls.redact(feed.getUrl());
        String state = !feed.isEnabled() ? "disabled"
                : (feed.getConsecutiveFailures() >= failingThreshold ? "failing" : "healthy");
        return new FeedResponse(
                feed.getId(),
                feed.getName(),
                url,
                feed.getSiteUrl(),
                feed.getTopic(),
                feed.isEnabled(),
                SourceSummary.of(feed.getSource()),
                feed.getCreatedAt(),
                feed.getLastFetchedAt(),
                feed.getLastSuccessAt(),
                feed.getLastError(),
                feed.getConsecutiveFailures(),
                state);
    }

    public static FeedResponse of(Feed feed, boolean admin) {
        return of(feed, admin, DEFAULT_FAILING_THRESHOLD);
    }
}
