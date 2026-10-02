package com.j11a.argus.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://example.test/a", "HTTPS://Example.test", "https://[::1]:8443/x"})
    void isHttpAcceptsAbsoluteHttpAndHttpsUrlsWithAHost(String url) {
        assertThat(HttpUrls.isHttp(URI.create(url))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.test/a", "javascript:alert(1)", "data:text/plain,x", "/relative",
            "http:///nohost", "mailto:a@example.test"})
    void isHttpRejectsEverythingElse(String url) {
        assertThat(HttpUrls.isHttp(URI.create(url))).isFalse();
    }

    @Test
    void parseHttpTrimsAndReturnsTheUri() {
        assertThat(HttpUrls.parseHttp("  https://example.test/a?b=1 "))
                .contains(URI.create("https://example.test/a?b=1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ftp://example.test", "http://bad host/", "not a url"})
    void parseHttpIsEmptyForAnythingElse(String raw) {
        assertThat(HttpUrls.parseHttp(raw)).isEmpty();
    }

    @Test
    void hasUserInfoSeesCredentialsOnly() {
        assertThat(HttpUrls.hasUserInfo(URI.create("http://user:pass@example.test/f"))).isTrue();
        assertThat(HttpUrls.hasUserInfo(URI.create("http://user@example.test/f"))).isTrue();
        assertThat(HttpUrls.hasUserInfo(URI.create("http://example.test/f@x"))).isFalse();
    }

    @Test
    void resolveHandlesRelativeAbsoluteAndRootRelativeReferences() {
        URI base = URI.create("https://example.test/news/feed.xml");

        assertThat(HttpUrls.resolve(base, "other.xml")).contains(URI.create("https://example.test/news/other.xml"));
        assertThat(HttpUrls.resolve(base, "/top.xml")).contains(URI.create("https://example.test/top.xml"));
        assertThat(HttpUrls.resolve(base, "http://else.test/a")).contains(URI.create("http://else.test/a"));
    }

    @Test
    void resolveAgainstAnEmptyPathKeepsTheSlash() {
        assertThat(HttpUrls.resolve(URI.create("https://example.test"), "feed.xml"))
                .contains(URI.create("https://example.test/feed.xml"));
    }

    @Test
    void resolveIsEmptyForAnUnparseableReference() {
        assertThat(HttpUrls.resolve(URI.create("https://example.test/"), "http://bad host/")).isEmpty();
    }

    @Test
    void redactKeepsSchemeHostPortAndPathOnly() {
        assertThat(HttpUrls.redact("https://user:pw@Feeds.example.test:8443/a%20b/f.xml?token=abc#frag"))
                .isEqualTo("https://Feeds.example.test:8443/a%20b/f.xml");
        assertThat(HttpUrls.redact("http://[::1]:9000/x?t=1")).isEqualTo("http://[::1]:9000/x");
        assertThat(HttpUrls.redact("https://example.test?token=abc")).isEqualTo("https://example.test");
    }

    @Test
    void redactNeverEchoesAValueItCannotParse() {
        assertThat(HttpUrls.redact("not a url?token=abc")).isEmpty();
    }
}
