package com.j11a.argus.source;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class SourceResolver {

    private SourceResolver() {
    }

    public static String keyFor(@Nullable String siteLink, URI feedUrl) {
        String host = hostOf(siteLink);
        if (host == null) {
            host = normalise(feedUrl.getHost());
        }
        return host == null ? "" : registrableDomain(host);
    }

    private static @Nullable String hostOf(@Nullable String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(link.strip());
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return http ? normalise(uri.getHost()) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static @Nullable String normalise(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static String registrableDomain(String host) {
        if (InetAddresses.isInetAddress(host.replaceAll("^\\[|]$", ""))) {
            return host;
        }
        try {
            InternetDomainName name = InternetDomainName.from(host);
            return name.isUnderPublicSuffix() ? name.topPrivateDomain().toString() : host;
        } catch (IllegalArgumentException e) {
            return host;
        }
    }
}
