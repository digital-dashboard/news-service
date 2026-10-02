package com.j11a.argus.article;

import com.j11a.argus.source.SourceSummary;
import java.time.Instant;
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
        SourceSummary source) {

    static ArticleResponse of(Article article) {
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
                SourceSummary.of(article.getSource()));
    }
}
