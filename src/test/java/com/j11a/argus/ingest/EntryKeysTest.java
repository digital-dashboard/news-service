package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class EntryKeysTest {

    @Test
    void urlGuidIsCleaned() {
        assertThat(EntryKeys.guidKey("https://www.x.com/a#0", null)).isEqualTo("https://x.com/a");
        assertThat(EntryKeys.guidKey("http://www.x.com/a/?utm_source=twitter", null)).isEqualTo("https://x.com/a");
    }

    @Test
    void nonUrlGuidIsKeptVerbatim() {
        assertThat(EntryKeys.guidKey("  urn:abc  ", "https://example.com/a")).isEqualTo("urn:abc");
        assertThat(EntryKeys.guidKey("tag:example.com,2026:item1", null)).isEqualTo("tag:example.com,2026:item1");
    }

    @Test
    void blankGuidUsesTheLinkKey() {
        assertThat(EntryKeys.guidKey(null, "HTTPS://www.Example.com/a#x")).isEqualTo("https://example.com/a");
        assertThat(EntryKeys.guidKey("   ", "HTTPS://www.Example.com/a#x")).isEqualTo("https://example.com/a");
    }

    @Test
    void missingGuidAndLinkOrInvalidLinkGiveNoKey() {
        assertThat(EntryKeys.guidKey(null, null)).isNull();
        assertThat(EntryKeys.guidKey(" ", "ftp://example.com/a")).isNull();
        assertThat(EntryKeys.linkKey(null)).isNull();
        assertThat(EntryKeys.linkKey("not-a-url")).isNull();
    }

    @Test
    void guidAtTheLimitIsKeptAndOneOverIsHashed() {
        String atLimit = "g".repeat(EntryKeys.MAX_KEY_LENGTH);
        String overLimit = atLimit + "g";

        assertThat(EntryKeys.guidKey(atLimit, null)).isEqualTo(atLimit);
        assertThat(EntryKeys.guidKey(overLimit, null)).isEqualTo("sha256:" + sha256Hex(overLimit));
    }

    @Test
    void longLinkKeyIsHashedAndShortOnesAreNot() {
        String longLink = "https://example.com/" + "p".repeat(EntryKeys.MAX_KEY_LENGTH);

        assertThat(EntryKeys.linkKey(longLink)).isEqualTo("sha256:" + sha256Hex(longLink));
        assertThat(EntryKeys.linkKey("https://www.Example.com/a#f")).isEqualTo("https://example.com/a");
    }

    @Test
    void hashedKeysAreBoundedAndDeterministic() {
        String key = EntryKeys.guidKey("é".repeat(2000), null);

        assertThat(key).hasSize("sha256:".length() + 64).isEqualTo(EntryKeys.guidKey("é".repeat(2000), null));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
