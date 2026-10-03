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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE feed
                SET etag = :etag,
                    last_modified = :lastModified,
                    last_fetched_at = :now,
                    last_success_at = :now,
                    last_error = NULL,
                    consecutive_failures = 0,
                    updated_at = :now
                WHERE id = :id
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNotModified(long feedId, FetchValidators validators, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE feed
                SET etag = COALESCE(:etag, etag),
                    last_modified = COALESCE(:lastModified, last_modified),
                    last_fetched_at = :now,
                    last_success_at = :now,
                    last_error = NULL,
                    consecutive_failures = 0,
                    updated_at = :now
                WHERE id = :id
                """)
                .param("id", feedId)
                .param("etag", validators.etag())
                .param("lastModified", validators.lastModified())
                .param("now", timestamp)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long feedId, String reason, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        String cappedReason = reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
        jdbc.sql("""
                UPDATE feed
                SET last_fetched_at = :now,
                    last_error = :reason,
                    consecutive_failures = consecutive_failures + 1,
                    updated_at = :now
                WHERE id = :id
                """)
                .param("id", feedId)
                .param("reason", cappedReason)
                .param("now", timestamp)
                .update();
    }
}
