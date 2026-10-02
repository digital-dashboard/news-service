package com.j11a.argus.feed.parse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class HttpUrls {

    private HttpUrls() {
    }

    /** Absolute values pass through as given; relative ones resolve against base. Unparseable values pass through. */
    static @Nullable String resolveLink(URI base, @Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value);
            return uri.isAbsolute() ? value : withDirectoryPath(base).resolve(uri).toString();
        } catch (URISyntaxException e) {
            return value;
        }
    }

    /** Like resolveLink, but only an absolute http or https result is accepted. */
    static @Nullable String resolveHttp(URI base, @Nullable String raw) {
        String resolved = resolveLink(base, raw);
        return resolved != null && isHttp(resolved) ? resolved : null;
    }

    static boolean isHttp(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null && scheme != null
                    && ("http".equals(scheme.toLowerCase(Locale.ROOT)) || "https".equals(scheme.toLowerCase(Locale.ROOT)));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // URI.resolve on "https://host" plus "a" yields "https://hosta".
    private static URI withDirectoryPath(URI base) {
        String path = base.getPath();
        return path == null || path.isEmpty() ? base.resolve("/") : base;
    }
}
