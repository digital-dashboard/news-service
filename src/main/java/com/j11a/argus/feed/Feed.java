package com.j11a.argus.feed;

import com.j11a.argus.source.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "feed")
public class Feed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, updatable = false)
    private Source source;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String url;

    @Column(name = "site_url")
    private @Nullable String siteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Topic topic;

    @Column(length = 16)
    private @Nullable String language;

    @Column(nullable = false)
    private boolean enabled;

    @Column
    private @Nullable String etag;

    @Column(name = "last_modified")
    private @Nullable String lastModified;

    @Column(name = "last_fetched_at")
    private @Nullable Instant lastFetchedAt;

    @Column(name = "last_success_at")
    private @Nullable Instant lastSuccessAt;

    @Column(name = "last_error")
    private @Nullable String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Feed() {
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public @Nullable String getSiteUrl() {
        return siteUrl;
    }

    public Topic getTopic() {
        return topic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public @Nullable String getEtag() {
        return etag;
    }

    public @Nullable String getLastModified() {
        return lastModified;
    }

    public @Nullable Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public @Nullable Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public @Nullable String getLastError() {
        return lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
