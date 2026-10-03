package com.j11a.argus.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class FeedSummaryTest {

    @Test
    void summaryCopiesIdNameAndTopicFromTheFeed() {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(42L);
        when(feed.getName()).thenReturn("Tech News");
        when(feed.getTopic()).thenReturn(Topic.TECH);

        FeedSummary summary = FeedSummary.of(feed);

        assertThat(summary.id()).isEqualTo(42L);
        assertThat(summary.name()).isEqualTo("Tech News");
        assertThat(summary.topic()).isEqualTo(Topic.TECH);
    }
}
