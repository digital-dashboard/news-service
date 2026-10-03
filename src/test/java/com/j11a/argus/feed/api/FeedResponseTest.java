package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedState;
import com.j11a.argus.source.Source;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeedResponseTest {

    private static final int FAILING_THRESHOLD = 3;
    private static final String URL = "https://user:pw@feeds.example.test:8443/a%20b/f.xml?token=abc";

    private static Feed feed() {
        Feed feed = mock(Feed.class);
        Source source = mock(Source.class);
        when(source.getKey()).thenReturn("example.test");
        when(feed.getId()).thenReturn(1L);
        when(feed.getName()).thenReturn("Example");
        when(feed.getUrl()).thenReturn(URL);
        when(feed.getSiteUrl()).thenReturn("https://example.test/?ref=site");
        when(feed.getTopic()).thenReturn(Topic.TECH);
        when(feed.getSource()).thenReturn(source);
        when(feed.getCreatedAt()).thenReturn(Instant.parse("2026-10-02T10:00:00Z"));
        return feed;
    }

    @Test
    void anAdminSeesTheFullUrl() {
        assertThat(FeedResponse.of(feed(), true, FAILING_THRESHOLD).url()).isEqualTo(URL);
    }

    @Test
    void everyoneElseSeesNeitherUserInfoNorQuery() {
        assertThat(FeedResponse.of(feed(), false, FAILING_THRESHOLD).url()).isEqualTo("https://feeds.example.test:8443/a%20b/f.xml");
    }

    @Test
    void theSiteUrlIsLeftAsItIsForEveryone() {
        assertThat(FeedResponse.of(feed(), false, FAILING_THRESHOLD).siteUrl()).isEqualTo("https://example.test/?ref=site");
    }

    @Test
    void theStateComesFromTheFailureCountAndTheThreshold() {
        Feed failing = feed();
        when(failing.isEnabled()).thenReturn(true);
        when(failing.getConsecutiveFailures()).thenReturn(FAILING_THRESHOLD);

        assertThat(FeedResponse.of(failing, false, FAILING_THRESHOLD).state()).isEqualTo(FeedState.FAILING);
        assertThat(FeedResponse.of(failing, false, FAILING_THRESHOLD + 1).state()).isEqualTo(FeedState.HEALTHY);
    }
}
