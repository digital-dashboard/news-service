package com.j11a.argus.article;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record NewArticle(
        long sourceId,
        String guidKey,
        @Nullable String rawGuid,
        @Nullable String linkKey,
        @Nullable String link,
        String contentHash,
        String title,
        String excerpt,
        @Nullable String author,
        @Nullable String imageUrl,
        List<String> categories,
        @Nullable Instant publishedAt,
        @Nullable Instant updatedAtUpstream,
        Instant effectiveAt,
        Instant fetchedAt) {
}
