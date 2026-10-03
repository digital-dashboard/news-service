package com.j11a.argus.feed.health;

import com.j11a.argus.feed.fetch.FetchValidators;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FeedHealthUpdater {

    private static final int MAX_ERROR_LENGTH = 128;

    private final JdbcClient jdbc;

    public FeedHealthUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the consecutive failures the feed had before this success reset them. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordSuccess(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                WITH previous AS (
                    SELECT consecutive_failures FROM feed WHERE id = :id FOR UPDATE
                ), reset AS (
                    UPDATE feed
                    SET etag = :etag,
                        last_modified = :lastModified,
                        last_fetched_at = :now,
                        last_success_at = :now,
                        last_error = NULL,
                        consecutive_failures = 0,
                        updated_at = :now
                    WHERE id = :id
                    RETURNING id
                )
                SELECT consecutive_failures FROM previous
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Returns the consecutive failures the feed had before this 304 reset them. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordNotModified(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                WITH previous AS (
                    SELECT consecutive_failures FROM feed WHERE id = :id FOR UPDATE
                ), reset AS (
                    UPDATE feed
                    SET etag = COALESCE(:etag, etag),
                        last_modified = COALESCE(:lastModified, last_modified),
                        last_fetched_at = :now,
                        last_success_at = :now,
                        last_error = NULL,
                        consecutive_failures = 0,
                        updated_at = :now
                    WHERE id = :id
                    RETURNING id
                )
                SELECT consecutive_failures FROM previous
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Returns the new consecutive failure count. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(long feedId, String reason, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        String cappedReason = reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
        return jdbc.sql("""
                UPDATE feed
                SET last_fetched_at = :now,
                    last_error = :reason,
                    consecutive_failures = consecutive_failures + 1,
                    updated_at = :now
                WHERE id = :id
                RETURNING consecutive_failures
                """)
                .param("id", feedId)
                .param("reason", cappedReason)
                .param("now", timestamp)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
