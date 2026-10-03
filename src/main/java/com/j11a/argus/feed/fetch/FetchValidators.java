package com.j11a.argus.feed.fetch;

import org.jspecify.annotations.Nullable;

public record FetchValidators(@Nullable String etag, @Nullable String lastModified) {

    public static final FetchValidators EMPTY = new FetchValidators(null, null);
}
