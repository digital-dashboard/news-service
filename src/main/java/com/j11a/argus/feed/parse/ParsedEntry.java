package com.j11a.argus.feed.parse;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ParsedEntry(
        @Nullable String guid,
        @Nullable String link,
        String title,
        String excerpt,
        @Nullable String author,
        @Nullable String imageUrl,
        List<String> categories,
        @Nullable Instant publishedAt,
        @Nullable Instant updatedAt) {

    public ParsedEntry {
        title = title == null ? "" : title.strip();
        excerpt = excerpt == null ? "" : excerpt;
        categories = List.copyOf(categories);
    }
}
