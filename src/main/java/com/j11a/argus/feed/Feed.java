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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Feed() {
    }

    public Feed(Source source, String name, String url, @Nullable String siteUrl, Topic topic,
            @Nullable String language, Instant now) {
        this.source = source;
        this.name = name;
        this.url = url;
        this.siteUrl = siteUrl;
        this.topic = topic;
        this.language = language;
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
