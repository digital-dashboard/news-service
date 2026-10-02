package com.j11a.argus.feed;

import com.j11a.argus.source.SourceSummary;
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

    static FeedResponse of(Feed feed) {
        return new FeedResponse(feed.getId(), feed.getName(), feed.getUrl(), feed.getSiteUrl(), feed.getTopic(),
                feed.isEnabled(), SourceSummary.of(feed.getSource()), feed.getCreatedAt());
    }
}
