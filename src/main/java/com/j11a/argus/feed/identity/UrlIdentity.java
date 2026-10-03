package com.j11a.argus.feed.identity;

import java.net.URI;
import java.util.Locale;

/** Comparison form of a stored URL, so that spellings of one feed address compare equal. Never stored or logged. */
public final class UrlIdentity {

    private static final String WWW = "www.";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private UrlIdentity() {
    }

    /** The input is StoredUrls.clean output. The query is kept verbatim: it can select a different feed. */
    public static String fold(String cleanedUrl) {
        URI uri = URI.create(cleanedUrl);
        StringBuilder folded = new StringBuilder();
        if (uri.getRawUserInfo() != null) {
            folded.append(uri.getRawUserInfo()).append('@');
        }
        folded.append(withoutWww(uri.getHost().toLowerCase(Locale.ROOT)));
        if (uri.getPort() != -1 && uri.getPort() != defaultPort(uri.getScheme())) {
            folded.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        folded.append(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        if (uri.getRawQuery() != null) {
            folded.append('?').append(uri.getRawQuery());
        }
        return folded.toString();
    }

    private static String withoutWww(String host) {
        return host.startsWith(WWW) && host.length() > WWW.length() ? host.substring(WWW.length()) : host;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? HTTPS_PORT : HTTP_PORT;
    }
}
