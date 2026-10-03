package com.j11a.argus.url;

import com.google.common.net.InternetDomainName;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class LinkCleaner {

    private static final List<String> HOST_PREFIXES = List.of("www.", "m.", "amp.");

    private LinkCleaner() {
    }

    /** Strips a leading www./m./amp. only when the rest is not a public suffix, so www.co.uk stays as it is. */
    public static @Nullable String clean(@Nullable String link) {
        return HttpUrls.parseHttp(link).map(LinkCleaner::rebuild).orElse(null);
    }

    public static boolean isRoot(@Nullable String cleanedLink) {
        if (cleanedLink == null) {
            return false;
        }
        return HttpUrls.parseHttp(cleanedLink)
                .map(uri -> (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null)
                .orElse(false);
    }

    private static String rebuild(URI uri) {
        String host = cleanHost(uri.getHost());
        String path = cleanPath(uri.getRawPath());
        List<String> querySegments = cleanQuery(uri.getRawQuery());

        StringBuilder sb = new StringBuilder("https://").append(host);
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (!querySegments.isEmpty()) {
            sb.append('?').append(String.join("&", querySegments));
        }
        return sb.toString();
    }

    private static String cleanHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : HOST_PREFIXES) {
                if (host.startsWith(prefix)) {
                    String remainder = host.substring(prefix.length());
                    if (remainder.contains(".") && !isPublicSuffix(remainder)) {
                        host = remainder;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return host;
    }

    private static boolean isPublicSuffix(String host) {
        try {
            return InternetDomainName.isValid(host) && InternetDomainName.from(host).isPublicSuffix();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String cleanPath(@Nullable String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String path = rawPath;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
                changed = true;
            } else if (path.toLowerCase(Locale.ROOT).endsWith("/amp")) {
                path = path.substring(0, path.length() - 4);
                changed = true;
            }
        }
        return path;
    }

    private static List<String> cleanQuery(@Nullable String rawQuery) {
        if (rawQuery == null) {
            return List.of();
        }
        List<String> surviving = new ArrayList<>();
        String[] segments = rawQuery.split("&", -1);
        for (String segment : segments) {
            if (!isDroppedParam(segment)) {
                surviving.add(segment);
            }
        }
        Collections.sort(surviving);
        return surviving;
    }

    private static boolean isDroppedParam(String segment) {
        if (segment.isEmpty()) {
            return true;
        }
        int eq = segment.indexOf('=');
        String rawName = eq >= 0 ? segment.substring(0, eq) : segment;
        String rawValue = eq >= 0 ? segment.substring(eq + 1) : null;
        String decodedLowerName = URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return TrackingParams.isTracking(decodedLowerName)
                || (decodedLowerName.equals("amp") && "1".equals(rawValue))
                || (decodedLowerName.equals("outputtype") && "amp".equalsIgnoreCase(rawValue));
    }
}
