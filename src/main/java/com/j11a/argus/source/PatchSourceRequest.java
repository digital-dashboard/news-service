package com.j11a.argus.source;

import com.j11a.argus.feed.api.AbsoluteHttpUrl;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record PatchSourceRequest(
        @Size(min = 1, max = 255) @Nullable String name,
        @Size(max = 2048) @AbsoluteHttpUrl @Nullable String homepage,
        @Nullable String country) {

    public boolean isEmpty() {
        return name == null && homepage == null && country == null;
    }
}
