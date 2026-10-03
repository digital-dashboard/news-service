package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.source.SourceService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedHealthRecordDuplicateIT extends AbstractIntegrationTest {

    @Autowired
    private FeedHealthUpdater healthUpdater;

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    private long insert() {
        long sourceId = sources.findOrCreate("dup.test", null).getId();
        return inserter.insert(new NewFeed(sourceId, "F", "https://dup.test/f", null, null, Topic.TECH, null))
                .orElseThrow();
    }

    @Test
    void disablesTheFeedNamesTheOriginalAndKeepsTheFailureCount() {
        long id = insert();
        healthUpdater.recordFailure(id, "io", Instant.now());
        Instant now = Instant.parse("2026-10-03T10:00:00Z");

        healthUpdater.recordDuplicate(id, 7L, now);

        assertThat(jdbcClient.sql("""
                SELECT enabled, last_error, consecutive_failures, last_fetched_at, updated_at
                FROM feed WHERE id = :id
                """).param("id", id).query().singleRow())
                .containsEntry("enabled", false)
                .containsEntry("last_error", "duplicate of feed 7")
                .containsEntry("consecutive_failures", 1)
                .satisfies(row -> assertThat(((java.sql.Timestamp) row.get("last_fetched_at")).toInstant())
                        .isEqualTo(now.truncatedTo(ChronoUnit.MILLIS)));
    }

    @Test
    void aMissingFeedIsANoOp() {
        healthUpdater.recordDuplicate(12345L, 7L, Instant.now());

        assertThat(count("SELECT count(*) FROM feed")).isZero();
    }
}
