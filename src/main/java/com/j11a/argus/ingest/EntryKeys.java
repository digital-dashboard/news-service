package com.j11a.argus.ingest;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Interim identity keys; phase 4 replaces the link cleaning and re-keys stored articles. */
public final class EntryKeys {

    public static final int MAX_KEY_LENGTH = 512;
    private static final String HASH_PREFIX = "sha256:";

    private EntryKeys() {
    }

    public static @Nullable String cleanLink(@Nullable String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(link.strip());
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!http || uri.getHost() == null) {
                return null;
            }
            return rebuild(uri);
        } catch (URISyntaxException e) {
            return null;
        }
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
        if (uri.getRawPath() != null) {
            cleaned.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            cleaned.append('?').append(uri.getRawQuery());
        }
        return cleaned.toString();
    }

    public static @Nullable String guidKey(@Nullable String rawGuid, @Nullable String link) {
        String guid = rawGuid == null ? "" : rawGuid.strip();
        return capped(guid.isEmpty() ? cleanLink(link) : guid);
    }

    public static @Nullable String linkKey(@Nullable String link) {
        return capped(cleanLink(link));
    }

    private static @Nullable String capped(@Nullable String key) {
        if (key == null || key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return HASH_PREFIX + HexFormat.of().formatHex(sha256(key.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JDK", e);
        }
    }
}
