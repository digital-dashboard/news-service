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
        Instant createdAt) {

    /** A feed URL can carry a token, so anyone but an admin sees it without user-info and query string. */
    static FeedResponse of(Feed feed, boolean admin) {
        String url = admin ? feed.getUrl() : HttpUrls.redact(feed.getUrl());
        return new FeedResponse(feed.getId(), feed.getName(), url, feed.getSiteUrl(), feed.getTopic(),
                feed.isEnabled(), SourceSummary.of(feed.getSource()), feed.getCreatedAt());
    }
}
