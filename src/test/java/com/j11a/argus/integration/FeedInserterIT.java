package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.SourceService;
import java.util.Optional;
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
        return new NewFeed(sourceId, "Example", URL, null, null, Topic.TECH, null);
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
    void aFeedWhoseSelfUrlIsTakenIsNotInsertedAndTheSelfUrlIsStored() {
        long sourceId = sources.findOrCreate("example.test", "https://example.test/").getId();
        String selfUrl = "https://example.test/self.xml";
        long first = inserter.insert(new NewFeed(sourceId, "A", URL, null, selfUrl, Topic.TECH, null)).orElseThrow();

        Optional<Long> second = inserter.insert(
                new NewFeed(sourceId, "B", "https://other.test/rss.xml", null, selfUrl, Topic.TECH, null));

        assertThat(second).isEmpty();
        assertThat(jdbcClient.sql("SELECT self_url FROM feed WHERE id = :id").param("id", first)
                .query(String.class).single()).isEqualTo(selfUrl);
        assertThat(count("SELECT count(*) FROM feed")).isEqualTo(1);
    }

    @Test
    void manyFeedsWithoutASelfUrlCanBeInserted() {
        long sourceId = sources.findOrCreate("example.test", "https://example.test/").getId();

        assertThat(inserter.insert(new NewFeed(sourceId, "A", "https://a.test/f", null, null, Topic.TECH, null)))
                .isPresent();
        assertThat(inserter.insert(new NewFeed(sourceId, "B", "https://b.test/f", null, null, Topic.TECH, null)))
                .isPresent();
    }

    @Test
    void findIdByUrlIsEmptyForAnUnknownUrl() {
        assertThat(inserter.findIdByUrl("https://nowhere.test/feed")).isEmpty();
    }
}
