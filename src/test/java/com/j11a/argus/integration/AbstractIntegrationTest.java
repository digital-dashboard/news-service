package com.j11a.argus.integration;

import static org.awaitility.Awaitility.await;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.api.FeedService;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.IngestMeters;
import com.j11a.argus.testsupport.ProbeController;
import com.j11a.argus.testsupport.RssBody;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every IT extends this, so one Spring context and one Postgres container serve the whole run.
 * MockMvc still goes through the full filter chain, including security and observation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("it")
@Import({PostgresContainerConfig.class, OtlpStubConfig.class, FeedStubConfig.class, SpanCollectorConfig.class,
        ProbeController.class})
public abstract class AbstractIntegrationTest {

    /** How long a test waits for another thread, a lock or a transaction before it gives up. */
    protected static final long LOCK_WAIT_SECONDS = 20;
    protected static final int SOURCE_LOCK_NAMESPACE = 4100;

    private static final String CLEAN_TABLES = "TRUNCATE article_feed, article, feed, source RESTART IDENTITY CASCADE";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected FeedStubServer stub;

    @Autowired
    protected FeedService feedService;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql(CLEAN_TABLES).update();
    }

    protected IngestMeters meters() {
        return new IngestMeters(meterRegistry);
    }

    protected long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    /** True when some session is blocked on the advisory lock of this source. */
    protected boolean isWaitingForSourceLock(long sourceId) {
        return count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = "
                + SOURCE_LOCK_NAMESPACE + " AND objid = " + sourceId) > 0;
    }

    protected void awaitWaitingForSourceLock(long sourceId) {
        await().atMost(Duration.ofSeconds(LOCK_WAIT_SECONDS)).until(() -> isWaitingForSourceLock(sourceId));
    }

    protected void awaitAnyAdvisoryWaiter() {
        await().atMost(Duration.ofSeconds(LOCK_WAIT_SECONDS)).until(() -> count(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0);
    }

    /**
     * Serves the fixture at path on the stub host, with a self link of its own so that several feeds can share one
     * fixture, and registers a feed for it, which also ingests it.
     */
    protected FeedResponse createFeedFrom(String path, String fixture, Topic topic) {
        stub.serve(path, 200, RssBody.CONTENT_TYPE,
                RssBody.withSelfLink(Fixtures.feed(fixture), stub.baseUrl() + path));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null, topic, null));
    }
}
