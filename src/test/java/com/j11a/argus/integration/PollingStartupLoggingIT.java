package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.poll.PollingStartupLogger;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.PatchRequests;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PollingStartupLoggingIT extends AbstractIntegrationTest {

    @Autowired
    private PollingStartupLogger startupLogger;

    @Autowired
    private FeedHealthUpdater healthUpdater;

    @Test
    void theStartupLineCountsEnabledAndFailingFeeds() {
        long failing = createFeedFrom("/startup/a.xml", "bbc-like-rss2.xml", Topic.NEWS).id();
        createFeedFrom("/startup/b.xml", "atom10.xml", Topic.WORLD);
        long disabled = createFeedFrom("/startup/c.xml", "rss091.xml", Topic.TECH).id();
        for (int i = 0; i < 3; i++) {
            healthUpdater.recordFailure(failing, "io", Instant.now());
        }
        feedService.patch(disabled, PatchRequests.enabled(false));

        try (LogCapture logs = LogCapture.start()) {
            startupLogger.logPollingSchedule();

            assertThat(logs.at(Level.INFO)).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("concurrency 2")
                        .contains("2 enabled feeds, 1 failing");
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("enabledFeeds", 2L)
                        .containsEntry("failingFeeds", 1L)
                        .containsEntry("concurrency", 2);
            });
        }
    }
}
