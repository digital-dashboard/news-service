package com.j11a.argus.source;

import org.jspecify.annotations.Nullable;

public record SourceSummary(long id, String name, @Nullable String homepage, @Nullable String country) {

    public static SourceSummary of(Source source) {
        return new SourceSummary(source.getId(), source.getName(), source.getHomepageUrl(), source.getCountry());
    }
}
