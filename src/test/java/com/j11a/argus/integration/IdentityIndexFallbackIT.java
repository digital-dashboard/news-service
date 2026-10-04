package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.j11a.argus.feed.api.PatchFeedRequest;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.identity.FeedRedirectApplier.RedirectOutcome;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.testsupport.RssBody;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The identity lock makes the unique indexes a backstop that no honest writer reaches. These tests reach it the only
 * way left: a trigger holds a URL change at the write until the test has inserted the conflicting row by plain SQL,
 * without the lock, and committed it.
 */
class IdentityIndexFallbackIT extends AbstractIntegrationTest {

    private static final int GATE_NAMESPACE = 9999;
    private static final String SOURCE_KEY = "gate.test";
    private static final String GATE_PATH = "/gate/new.xml";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private FeedRedirectApplier applier;

    private MergeData data;
    private long sourceId;

    @BeforeEach
    void installGate() {
        jdbcClient.sql("""
                CREATE FUNCTION wait_at_test_gate() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d, 1);
                    RETURN NEW;
                END $$ LANGUAGE plpgsql
                """.formatted(GATE_NAMESPACE)).update();
        jdbcClient.sql("""
                CREATE TRIGGER wait_at_test_gate BEFORE UPDATE OF url ON feed
                FOR EACH ROW WHEN (NEW.url LIKE '%/gate/%') EXECUTE FUNCTION wait_at_test_gate()
                """).update();
        data = new MergeData(jdbcClient);
        sourceId = data.source(SOURCE_KEY, null);
    }

    @AfterEach
    void removeGate() {
        jdbcClient.sql("DROP TRIGGER wait_at_test_gate ON feed").update();
        jdbcClient.sql("DROP FUNCTION wait_at_test_gate()").update();
    }

    private String url(String path) {
        return stub.baseUrl() + path;
    }

    /**
     * Runs the action with the gate closed, so it stops at the guarded write; inserts the holder of the gate URL by
     * plain SQL meanwhile; then opens the gate and returns what the action did.
     */
    private <T> T throughTheGate(Supplier<T> action, AtomicLong holderId) throws Exception {
        try (Connection gate = dataSource.getConnection(); Statement statement = gate.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + GATE_NAMESPACE + ", 1)");
            CompletableFuture<T> running = CompletableFuture.supplyAsync(action);
            await().atMost(Duration.ofSeconds(LOCK_WAIT_SECONDS)).until(this::someoneIsWaitingAtTheGate);
            holderId.set(data.feed(sourceId, url(GATE_PATH)));
            open(statement);
            return running.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void open(Statement statement) throws SQLException {
        statement.execute("SELECT pg_advisory_unlock(" + GATE_NAMESPACE + ", 1)");
    }

    private boolean someoneIsWaitingAtTheGate() {
        return count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = "
                + GATE_NAMESPACE) > 0;
    }

    @Test
    void aUrlChangeThatLosesTheRaceToTheUniqueIndexIs409NamingTheFeedThatWon() {
        long id = data.feed(sourceId, url("/own/old.xml"));
        stub.serve(GATE_PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://gate.example.test/", null, "g1"));
        AtomicLong holder = new AtomicLong();

        assertThatThrownBy(() -> throughTheGate(() -> feedService.patch(id,
                new PatchFeedRequest(null, null, null, null, url(GATE_PATH))), holder))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_URL_CONFLICT);
                    assertThat(e.properties())
                            .containsEntry("existingFeedId", holder.get())
                            .containsEntry("kind", "entered");
                });
        assertThat(jdbcClient.sql("SELECT url FROM feed WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo(url("/own/old.xml"));
    }

    @Test
    void aRedirectThatLosesTheRaceToTheUniqueIndexDisablesTheFeedAsADuplicateOfTheFeedThatWon() throws Exception {
        String oldUrl = url("/own/old-redirect.xml");
        long id = data.feed(sourceId, oldUrl);
        AtomicLong holder = new AtomicLong();

        RedirectOutcome outcome = throughTheGate(
                () -> applier.apply(id, SOURCE_KEY, oldUrl, URI.create(url(GATE_PATH))), holder);

        assertThat(outcome).isEqualTo(new RedirectOutcome.Conflict(holder.get()));
        assertThat(jdbcClient.sql("SELECT enabled, last_error, url FROM feed WHERE id = :id")
                .param("id", id).query().singleRow())
                .containsEntry("enabled", false)
                .containsEntry("last_error", "duplicate of feed " + holder.get())
                .containsEntry("url", oldUrl);
    }
}
