package com.j11a.argus.feed.parse;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record ParsedFeed(
        String title,
        @Nullable String siteLink,
        @Nullable String selfLink,
        @Nullable String language,
        List<ParsedEntry> entries) {

    public ParsedFeed {
        title = title == null ? "" : title.strip();
        entries = List.copyOf(entries);
    }
}
