package com.j11a.argus.integration;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.api.FeedService;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.ProbeController;
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

    private static final String CLEAN_TABLES = "TRUNCATE article_feed, article, feed, source RESTART IDENTITY CASCADE";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected FeedStubServer stub;

    @Autowired
    protected FeedService feedService;

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql(CLEAN_TABLES).update();
    }

    /** Serves the fixture at path on the stub host and registers a feed for it, which also ingests it. */
    protected FeedResponse createFeedFrom(String path, String fixture, Topic topic) {
        stub.serveFixture(path, fixture);
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null, topic));
    }
}
