package com.j11a.argus.ingest;

import com.j11a.argus.url.Links;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/** Interim identity keys; phase 4 re-keys stored articles. */
public final class EntryKeys {

    public static final int MAX_KEY_LENGTH = 512;
    private static final String HASH_PREFIX = "sha256:";

    private EntryKeys() {
    }

    public static @Nullable String guidKey(@Nullable String rawGuid, @Nullable String link) {
        String guid = rawGuid == null ? "" : rawGuid.strip();
        return capped(guid.isEmpty() ? Links.clean(link) : guid);
    }

    public static @Nullable String linkKey(@Nullable String link) {
        return capped(Links.clean(link));
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
