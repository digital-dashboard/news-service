package com.j11a.argus.config;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Clocks {

    private Clocks() {
    }

    /** The clock's current instant as the UTC timestamp that timestamptz parameters expect. */
    public static OffsetDateTime utcNow(Clock clock) {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
