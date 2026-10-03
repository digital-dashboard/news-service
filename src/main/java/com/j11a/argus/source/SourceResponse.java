package com.j11a.argus.source;

import com.j11a.argus.feed.FeedSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record SourceResponse(
        long id,
        String key,
        String name,
        @Nullable String homepage,
        @Nullable String country,
        long articleCount,
        List<FeedSummary> feeds) {

    public SourceResponse {
        feeds = List.copyOf(feeds);
    }
}
