package com.j11a.argus.ingest;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public final class EffectiveTime {

    private EffectiveTime() {
    }

    public static Instant of(@Nullable Instant published, @Nullable Instant updated, Instant fetchedAt) {
        Instant latest = published == null ? updated : (updated == null || published.isAfter(updated) ? published : updated);
        return latest == null || latest.isAfter(fetchedAt) ? fetchedAt : latest;
    }
}
