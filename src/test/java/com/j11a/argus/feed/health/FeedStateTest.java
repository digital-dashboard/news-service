package com.j11a.argus.feed.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeedStateTest {

    @Test
    void aDisabledFeedIsDisabledWhateverItsFailureCount() {
        assertThat(FeedState.of(false, 0, 3)).isEqualTo(FeedState.DISABLED);
        assertThat(FeedState.of(false, 10, 3)).isEqualTo(FeedState.DISABLED);
    }

    @Test
    void anEnabledFeedFailsFromTheThresholdUp() {
        assertThat(FeedState.of(true, 2, 3)).isEqualTo(FeedState.HEALTHY);
        assertThat(FeedState.of(true, 3, 3)).isEqualTo(FeedState.FAILING);
    }

    @Test
    void theTagIsTheLowercaseName() {
        assertThat(FeedState.FAILING.tag()).isEqualTo("failing");
    }
}
