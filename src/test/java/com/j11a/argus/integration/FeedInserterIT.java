package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.SourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedInserterIT extends AbstractIntegrationTest {

    private static final String URL = "https://example.test/rss.xml?token=abc";

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    private NewFeed newFeed() {
        long sourceId = sources.findOrCreate("example.test", "https://example.test/").getId();
        return new NewFeed(sourceId, "Example", URL, null, Topic.TECH, null);
    }

    @Test
    void theSecondInsertOfTheSameUrlReturnsNothingAndTheFirstIdIsFindable() {
        NewFeed feed = newFeed();

        long first = inserter.insert(feed).orElseThrow();

        assertThat(inserter.insert(feed)).isEmpty();
        assertThat(inserter.findIdByUrl(URL)).contains(first);
        assertThat(jdbcClient.sql("SELECT count(*) FROM feed").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void findIdByUrlIsEmptyForAnUnknownUrl() {
        assertThat(inserter.findIdByUrl("https://nowhere.test/feed")).isEmpty();
    }
}
