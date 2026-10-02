package com.j11a.argus.feed;

import org.jspecify.annotations.Nullable;

public record NewFeed(
        long sourceId,
        String name,
        String url,
        @Nullable String siteUrl,
        Topic topic,
        @Nullable String language) {
}
