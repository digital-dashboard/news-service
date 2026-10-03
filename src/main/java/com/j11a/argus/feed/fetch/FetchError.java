package com.j11a.argus.feed.fetch;

import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.LogSafe;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Why a fetch failed, for logs only: a type name and a message that never carries a query string or user-info,
 * is capped and has no control characters.
 */
public record FetchError(String type, @Nullable String message) {

    private static final int MAX_CONTENT_TYPE_LENGTH = 100;

    public FetchError {
        message = message == null ? null : LogSafe.sanitize(message, LogSafe.MAX_MESSAGE_LENGTH);
    }

    public static FetchError of(Throwable cause) {
        return new FetchError(LogSafe.errorType(cause), LogSafe.errorMessage(cause));
    }

    /** For failures whose exception text may quote data (a database constraint quotes the article row). */
    public static FetchError typeOnly(Throwable cause) {
        return new FetchError(LogSafe.errorType(cause), null);
    }

    /** A message that comes from outside the fetch code, so it is cleaned first. */
    public static FetchError ofMessage(String type, @Nullable String rawMessage) {
        return new FetchError(type, LogSafe.message(rawMessage));
    }

    /**
     * The one place that decides which failure details a log line carries: errorType, errorMessage, contentType and
     * bodyBytes, each only when known. The last two are for parse failures.
     */
    public static LoggingEventBuilder addFields(LoggingEventBuilder event, @Nullable FetchError error,
            @Nullable String contentType, @Nullable Integer bodyBytes) {
        LogFields.put(event, LogKeys.ERROR_TYPE, error != null ? error.type() : null);
        LogFields.put(event, LogKeys.ERROR_MESSAGE, error != null ? error.message() : null);
        LogFields.put(event, LogKeys.CONTENT_TYPE,
                contentType != null ? LogSafe.sanitize(contentType, MAX_CONTENT_TYPE_LENGTH) : null);
        return LogFields.put(event, LogKeys.BODY_BYTES, bodyBytes);
    }
}
