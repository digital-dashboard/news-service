package com.j11a.argus.feed;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Plain SQL rather than JPA: the unique url and self_url indexes decide, atomically, which of two concurrent creators wins. */
@Component
public class FeedInserter {

    private static final String INSERT = """
            INSERT INTO feed (source_id, name, url, site_url, self_url, topic, language, enabled, created_at, updated_at)
            VALUES (:sourceId, :name, :url, :siteUrl, :selfUrl, :topic, :language, true, :now, :now)
            ON CONFLICT DO NOTHING
            RETURNING id
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public FeedInserter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The new feed's id, or empty when a feed already has this url or self url. */
    public Optional<Long> insert(NewFeed feed) {
        return jdbc.sql(INSERT)
                .param("sourceId", feed.sourceId())
                .param("name", feed.name())
                .param("url", feed.url())
                .param("siteUrl", feed.siteUrl())
                .param("selfUrl", feed.selfUrl())
                .param("topic", feed.topic().name())
                .param("language", feed.language())
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findIdByUrl(String url) {
        return jdbc.sql("SELECT id FROM feed WHERE url = :url").param("url", url).query(Long.class).optional();
    }
}
