package com.j11a.argus.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** Rows are written by SourceService's upsert; JPA only reads them. */
@Entity
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(name = "homepage_url")
    private @Nullable String homepageUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(columnDefinition = "char(2)", length = 2)
    private @Nullable String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Source() {
    }

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getHomepageUrl() {
        return homepageUrl;
    }

    public @Nullable String getCountry() {
        return country;
    }
}
