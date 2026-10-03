package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.DecisionApplier;
import com.j11a.argus.ingest.PersistCounts;
import com.j11a.argus.ingest.dedup.EntryDecision;
import com.j11a.argus.ingest.dedup.KeyedEntry;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.testsupport.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConflictAbsorptionIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private DecisionApplier applier;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void anInsertThatLosesARaceIsAbsorbedAsAnInsertConflictUpdate() {
        FeedResponse feed = createFeedFrom("/conflict/feed.xml", "bbc-like-rss2.xml", Topic.WORLD);
        long sourceId = feed.source().id();
        long articleId = jdbcClient.sql("SELECT min(id) FROM article").query(Long.class).single();
        String guidKey = jdbcClient.sql("SELECT guid_key FROM article WHERE id = :id").param("id", articleId)
                .query(String.class).single();
        String titleBefore = jdbcClient.sql("SELECT title FROM article WHERE id = :id").param("id", articleId)
                .query(String.class).single();
        jdbcClient.sql("DELETE FROM article_feed WHERE article_id = :id").param("id", articleId).update();
        long articlesBefore = count("SELECT count(*) FROM article");

        ParsedEntry parsed = new ParsedEntry(guidKey, null, "A Conflicting Title", "excerpt", null, null, List.of(),
                null, null);
        KeyedEntry entry = new KeyedEntry(parsed, guidKey, null, "conflict-hash", NOW, 0);
        Resolution resolution = new Resolution(List.of(new EntryDecision.Insert(entry)), Map.of());

        PersistCounts counts;
        try (LogCapture logs = LogCapture.start()) {
            counts = new TransactionTemplate(txManager)
                    .execute(status -> applier.apply(sourceId, feed.id(), resolution, NOW, NOW));

            assertThat(logs.messagesAt(Level.WARN)).anySatisfy(message ->
                    assertThat(message).contains("Insert conflict").contains("existing article id " + articleId)
                            .doesNotContain(guidKey));
        }

        assertThat(counts).isNotNull();
        assertThat(counts.inserted()).isZero();
        assertThat(counts.updated()).isEqualTo(Map.of("insert_conflict", 1));
        assertThat(count("SELECT count(*) FROM article")).isEqualTo(articlesBefore);
        assertThat(count("SELECT count(*) FROM article_feed WHERE article_id = " + articleId)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT title FROM article WHERE id = :id").param("id", articleId)
                .query(String.class).single()).isEqualTo(titleBefore);
    }
}
