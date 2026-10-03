package com.j11a.argus.observability;

import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;

/** Structured log fields are emitted only when they apply, so an absent value adds nothing. */
public final class LogFields {

    private LogFields() {
    }

    public static LoggingEventBuilder put(LoggingEventBuilder event, String key, @Nullable Object value) {
        return value == null ? event : event.addKeyValue(key, value);
    }
}
