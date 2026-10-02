package com.j11a.argus.feed.parse;

import com.j11a.argus.url.HttpUrls;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Cleaning of the raw strings a feed hands us. */
final class RawValues {

    private RawValues() {
    }

    /** Absolute values pass through as given; relative ones resolve against base. Unparseable values pass through. */
    static @Nullable String resolveLink(URI base, @Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        return HttpUrls.resolve(base, value).map(URI::toString).orElse(value);
    }

    /** Like resolveLink, but only an absolute http or https result is accepted. */
    static @Nullable String resolveHttp(URI base, @Nullable String raw) {
        String resolved = resolveLink(base, raw);
        return HttpUrls.parseHttp(resolved).isPresent() ? resolved : null;
    }

    static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
