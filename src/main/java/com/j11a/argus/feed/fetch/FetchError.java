package com.j11a.argus.feed.fetch;

import com.j11a.argus.url.LogSafe;
import org.jspecify.annotations.Nullable;

/** Why a fetch failed, for logs only: a type name and a message that never carries a query string or user-info. */
public record FetchError(String type, @Nullable String message) {

    public static FetchError of(Throwable cause) {
        return new FetchError(LogSafe.errorType(cause), LogSafe.errorMessage(cause));
    }
}
