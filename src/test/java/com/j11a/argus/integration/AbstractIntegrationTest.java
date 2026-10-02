package com.j11a.argus.integration;

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

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql(CLEAN_TABLES).update();
    }
}
