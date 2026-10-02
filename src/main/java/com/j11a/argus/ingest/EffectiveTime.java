package com.j11a.argus.ingest;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public final class EffectiveTime {

    private EffectiveTime() {
    }

    /**
     * Later of published and updated, else fetch time; never after fetch time, so a future date cannot pin an
     * article to the top.
     */
    public static Instant of(@Nullable Instant published, @Nullable Instant updated, Instant fetchedAt) {
        Instant latest = later(published, updated);
        return latest == null || latest.isAfter(fetchedAt) ? fetchedAt : latest;
    }

    private static @Nullable Instant later(@Nullable Instant first, @Nullable Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }
}
