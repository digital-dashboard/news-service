package com.j11a.argus.feed.health;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum FeedState {
    HEALTHY,
    FAILING,
    DISABLED;

    private final String tag = name().toLowerCase(Locale.ROOT);

    @JsonValue
    public String tag() {
        return tag;
    }

    public static FeedState of(boolean enabled, int consecutiveFailures, int failingThreshold) {
        if (!enabled) {
            return DISABLED;
        }
        return consecutiveFailures >= failingThreshold ? FAILING : HEALTHY;
    }
}
