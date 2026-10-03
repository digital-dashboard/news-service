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

    /**
     * Returns the consecutive failures the feed had before this success reset them, or 0 when the feed no longer
     * exists. The subquery locks the row and reads the old count in the same statement as the reset, so exactly one
     * of several concurrent successes sees a count above 0.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordSuccess(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                UPDATE feed f
                SET etag = :etag,
                    last_modified = :lastModified,
                    last_fetched_at = :now,
                    last_success_at = :now,
                    last_error = NULL,
                    consecutive_failures = 0,
                    updated_at = :now
                FROM (SELECT id, consecutive_failures FROM feed WHERE id = :id FOR UPDATE) old
                WHERE f.id = old.id
                RETURNING old.consecutive_failures
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .query((rs, row) -> rs.getInt(1))
                .list()
                .stream()
                .mapToInt(Integer::intValue)
                .findFirst()
                .orElse(0);
    }

    /**
     * Returns the consecutive failures the feed had before this 304 reset them, or 0 when the feed no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordNotModified(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                UPDATE feed f
                SET etag = COALESCE(:etag, f.etag),
                    last_modified = COALESCE(:lastModified, f.last_modified),
                    last_fetched_at = :now,
                    last_success_at = :now,
                    last_error = NULL,
                    consecutive_failures = 0,
                    updated_at = :now
                FROM (SELECT id, consecutive_failures FROM feed WHERE id = :id FOR UPDATE) old
                WHERE f.id = old.id
                RETURNING old.consecutive_failures
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .query((rs, row) -> rs.getInt(1))
                .list()
                .stream()
                .mapToInt(Integer::intValue)
                .findFirst()
                .orElse(0);
    }

    /** Returns the new consecutive failure count, or 0 when the feed no longer exists. */
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
                .query((rs, row) -> rs.getInt(1))
                .list()
                .stream()
                .mapToInt(Integer::intValue)
                .findFirst()
                .orElse(0);
    }
}
