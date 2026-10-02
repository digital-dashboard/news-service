package com.j11a.argus.url;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The one definition of an acceptable http(s) URL, shared by every package that takes URLs from outside. */
public final class HttpUrls {

    private HttpUrls() {
    }

    public static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return uri.getHost() != null && scheme != null
                && ("http".equals(scheme.toLowerCase(Locale.ROOT)) || "https".equals(scheme.toLowerCase(Locale.ROOT)));
    }

    public static boolean hasUserInfo(URI uri) {
        return uri.getRawUserInfo() != null;
    }

    /** The trimmed value as a URI, if it is an absolute http or https URL with a host. */
    public static Optional<URI> parseHttp(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(raw.strip())).filter(HttpUrls::isHttp);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Resolves ref against base; absolute refs come back as they are. Empty when ref is not a valid URI. */
    public static Optional<URI> resolve(URI base, String ref) {
        try {
            return Optional.of(withDirectoryPath(base).resolve(new URI(ref.strip())));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Scheme, host, port and path only: no user-info, query or fragment. Safe to show to anyone. */
    public static String redact(String url) {
        return parseHttp(url).map(HttpUrls::withoutUserInfoAndQuery).orElse("");
    }

    private static String withoutUserInfoAndQuery(URI uri) {
        StringBuilder redacted = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            redacted.append(':').append(uri.getPort());
        }
        return redacted.append(uri.getRawPath()).toString();
    }

    // URI.resolve on "https://host" plus "a" yields "https://hosta".
    private static URI withDirectoryPath(URI base) {
        String path = base.getPath();
        return path == null || path.isEmpty() ? base.resolve("/") : base;
    }
}
