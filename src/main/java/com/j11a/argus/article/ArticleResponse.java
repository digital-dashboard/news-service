package com.j11a.argus.article;

import com.j11a.argus.feed.FeedSummary;
import com.j11a.argus.source.SourceSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ArticleResponse(
        long id,
        String title,
        String excerpt,
        @Nullable String author,
        @Nullable String link,
        @Nullable String imageUrl,
        List<String> categories,
        @Nullable Instant publishedAt,
        @Nullable Instant updatedAt,
        SourceSummary source,
        List<FeedSummary> feeds) {

    static ArticleResponse of(Article article) {
        List<FeedSummary> feeds = article.getFeeds().stream()
                .map(FeedSummary::of)
                .sorted(Comparator.comparingLong(FeedSummary::id))
                .toList();
        return new ArticleResponse(
                article.getId(),
                article.getTitle(),
                article.getExcerpt() == null ? "" : article.getExcerpt(),
                article.getAuthor(),
                article.getLink(),
                article.getImageUrl(),
                List.copyOf(article.getCategories()),
                article.getPublishedAt(),
                article.getUpdatedAtUpstream(),
                SourceSummary.of(article.getSource()),
                feeds);
    }
}
