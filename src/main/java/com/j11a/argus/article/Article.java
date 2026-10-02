package com.j11a.argus.article;

import com.j11a.argus.source.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** Read-only view: rows are written by ArticleInserter. */
@Entity
@Immutable
@Table(name = "article")
public class Article {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(nullable = false)
    private String title;

    private @Nullable String excerpt;

    private @Nullable String author;

    private @Nullable String link;

    @Column(name = "image_url")
    private @Nullable String imageUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> categories;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "updated_at_upstream")
    private @Nullable Instant updatedAtUpstream;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    protected Article() {
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public String getTitle() {
        return title;
    }

    public @Nullable String getExcerpt() {
        return excerpt;
    }

    public @Nullable String getAuthor() {
        return author;
    }

    public @Nullable String getLink() {
        return link;
    }

    public @Nullable String getImageUrl() {
        return imageUrl;
    }

    public List<String> getCategories() {
        return categories;
    }

    public @Nullable Instant getPublishedAt() {
        return publishedAt;
    }

    public @Nullable Instant getUpdatedAtUpstream() {
        return updatedAtUpstream;
    }
}
