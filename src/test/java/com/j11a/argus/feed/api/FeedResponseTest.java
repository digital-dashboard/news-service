package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.Source;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeedResponseTest {

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
        assertThat(FeedResponse.of(feed(), true).url()).isEqualTo(URL);
    }

    @Test
    void everyoneElseSeesNeitherUserInfoNorQuery() {
        assertThat(FeedResponse.of(feed(), false).url()).isEqualTo("https://feeds.example.test:8443/a%20b/f.xml");
    }

    @Test
    void theSiteUrlIsLeftAsItIsForEveryone() {
        assertThat(FeedResponse.of(feed(), false).siteUrl()).isEqualTo("https://example.test/?ref=site");
    }
}
