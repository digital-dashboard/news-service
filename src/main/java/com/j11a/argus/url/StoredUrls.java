package com.j11a.argus.url;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Light normalisation for URLs that are stored and fetched (feed, site and homepage URLs). Article identity keys use LinkCleaner. */
public final class StoredUrls {

    private StoredUrls() {
    }

    /** Trims, lowercases scheme and host, drops the fragment; null unless the link is an absolute http(s) URL. */
    public static @Nullable String clean(@Nullable String link) {
        return HttpUrls.parseHttp(link).map(StoredUrls::rebuild).orElse(null);
    }

    // Built from raw components: the multi-argument URI constructors would re-encode escapes.
    private static String rebuild(URI uri) {
        StringBuilder cleaned = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT)).append("://");
        if (uri.getRawUserInfo() != null) {
            cleaned.append(uri.getRawUserInfo()).append('@');
        }
        cleaned.append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            cleaned.append(':').append(uri.getPort());
        }
        cleaned.append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
            cleaned.append('?').append(uri.getRawQuery());
        }
        return cleaned.toString();
    }
}
