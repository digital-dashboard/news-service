package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EffectiveTimeTest {

    private static final Instant FETCHED = Instant.parse("2026-10-06T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-02T09:00:00Z");

    @Test
    void laterOfPublishedAndUpdatedWins() {
        assertThat(EffectiveTime.of(EARLIER, LATER, FETCHED)).isEqualTo(LATER);
        assertThat(EffectiveTime.of(LATER, EARLIER, FETCHED)).isEqualTo(LATER);
    }

    @Test
    void singleDateIsUsedAsIs() {
        assertThat(EffectiveTime.of(EARLIER, null, FETCHED)).isEqualTo(EARLIER);
        assertThat(EffectiveTime.of(null, LATER, FETCHED)).isEqualTo(LATER);
    }

    @Test
    void noDatesFallBackToFetchTime() {
        assertThat(EffectiveTime.of(null, null, FETCHED)).isEqualTo(FETCHED);
    }

    @Test
    void futureDatesAreCappedAtFetchTime() {
        Instant future = FETCHED.plusSeconds(86_400);

        assertThat(EffectiveTime.of(future, null, FETCHED)).isEqualTo(FETCHED);
        assertThat(EffectiveTime.of(EARLIER, future, FETCHED)).isEqualTo(FETCHED);
    }

    @Test
    void dateEqualToFetchTimeIsKept() {
        assertThat(EffectiveTime.of(FETCHED, null, FETCHED)).isEqualTo(FETCHED);
    }
}
