package com.j11a.argus.feed.fetch;

import java.util.Locale;

public enum FetchFailureReason {
    TIMEOUT,
    HTTP_STATUS,
    TOO_LARGE,
    INVALID_URL,
    REDIRECT_LIMIT,
    IO;

    private final String tag = name().toLowerCase(Locale.ROOT);

    public String tag() {
        return tag;
    }
}
