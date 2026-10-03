package com.j11a.argus.source;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import com.j11a.argus.url.HttpUrls;
import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class SourceResolver {

    /** source.key is varchar(255); real hosts are at most 253 characters, so this only guards hostile input. */
    static final int MAX_KEY_LENGTH = 255;

    private SourceResolver() {
    }

    public static String keyFor(@Nullable String siteLink, URI feedUrl) {
        String linkKey = keyOfLink(siteLink);
        return linkKey != null ? linkKey : hostKey(feedUrl);
    }

    /** The key of the feed's own host: its registrable domain. */
    public static String hostKey(URI feedUrl) {
        return keyOfHost(normalise(feedUrl.getHost()));
    }

    /** The key of an http(s) link's host, or null when the link is blank, relative or not http(s). */
    public static @Nullable String keyOfLink(@Nullable String link) {
        String host = hostOf(link);
        return host == null ? null : keyOfHost(host);
    }

    private static String keyOfHost(@Nullable String host) {
        String key = host == null ? "" : registrableDomain(host);
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
    }

    private static @Nullable String hostOf(@Nullable String link) {
        return HttpUrls.parseHttp(link).map(uri -> normalise(uri.getHost())).orElse(null);
    }

    private static @Nullable String normalise(@Nullable String host) {
        if (host == null) {
            return null;
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static String registrableDomain(String host) {
        if (InetAddresses.isUriInetAddress(host)) {
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
