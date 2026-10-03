package com.j11a.argus.ingest;

import com.j11a.argus.crypto.Sha256;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.LinkCleaner;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

public final class EntryKeys {

    public static final int MAX_KEY_LENGTH = 512;
    private static final String HASH_PREFIX = "sha256:";

    private EntryKeys() {
    }

    public static @Nullable String guidKey(@Nullable String rawGuid, @Nullable String link) {
        String guid = rawGuid == null ? "" : rawGuid.strip();
        if (guid.isEmpty()) {
            return linkKey(link);
        }
        if (HttpUrls.parseHttp(guid).isPresent()) {
            return capped(LinkCleaner.clean(guid));
        }
        return capped(guid);
    }

    public static @Nullable String linkKey(@Nullable String link) {
        return capped(LinkCleaner.clean(link));
    }

    private static @Nullable String capped(@Nullable String key) {
        if (key == null || key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return HASH_PREFIX + HexFormat.of().formatHex(Sha256.digest(key.getBytes(StandardCharsets.UTF_8)));
    }
}
