package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EntryKeysTest {

    @Test
    void cleanLinkLowercasesSchemeAndHostAndDropsTheFragment() {
        assertThat(EntryKeys.cleanLink("  HTTPS://News.Example.COM/Path/A?x=1#top ")).isEqualTo("https://news.example.com/Path/A?x=1");
    }

    @Test
    void cleanLinkKeepsWwwParameterOrderTrackingParametersPortAndEscapes() {
        String link = "http://www.example.com:8080/a%2Fb?utm_source=x&b=2&a=1";

        assertThat(EntryKeys.cleanLink(link)).isEqualTo(link);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "ftp://example.com/a", "file:///etc/passwd", "/relative/path", "mailto:a@b.c",
            "javascript:alert(1)", "https:///nohost", "http://bad host/", "not a url"})
    void cleanLinkRejectsAnythingThatIsNotAnAbsoluteHttpUrl(String link) {
        assertThat(EntryKeys.cleanLink(link)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP://Example.com/A#frag", "https://example.com", "https://[::1]:9000/x?y=%20z",
            "http://user:pw@Host.example/p"})
    void cleanLinkIsIdempotent(String link) {
        String once = EntryKeys.cleanLink(link);

        assertThat(once).isNotNull();
        assertThat(EntryKeys.cleanLink(once)).isEqualTo(once);
    }

    @Test
    void guidKeyPrefersTheTrimmedGuid() {
        assertThat(EntryKeys.guidKey("  urn:abc  ", "https://example.com/a")).isEqualTo("urn:abc");
    }

    @Test
    void guidKeyFallsBackToTheCleanedLinkWhenTheGuidIsNullOrBlank() {
        assertThat(EntryKeys.guidKey(null, "HTTPS://Example.com/a#x")).isEqualTo("https://example.com/a");
        assertThat(EntryKeys.guidKey("   ", "HTTPS://Example.com/a#x")).isEqualTo("https://example.com/a");
    }

    @Test
    void guidKeyIsNullWithoutGuidAndWithoutUsableLink() {
        assertThat(EntryKeys.guidKey(null, null)).isNull();
        assertThat(EntryKeys.guidKey(" ", "ftp://example.com/a")).isNull();
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
        assertThat(EntryKeys.linkKey("https://Example.com/a#f")).isEqualTo("https://example.com/a");
        assertThat(EntryKeys.linkKey("nope")).isNull();
        assertThat(EntryKeys.linkKey(null)).isNull();
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
