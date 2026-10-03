package com.j11a.argus.feed.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.url.StoredUrls;
import org.junit.jupiter.api.Test;

class UrlIdentityTest {

    private static String fold(String url) {
        return UrlIdentity.fold(StoredUrls.clean(url));
    }

    @Test
    void schemeIsIgnored() {
        assertThat(fold("http://example.com/feed")).isEqualTo(fold("https://example.com/feed"));
    }

    @Test
    void hostIsCaseInsensitive() {
        assertThat(fold("https://EXAMPLE.com/Feed")).isEqualTo(fold("https://example.com/Feed"));
    }

    @Test
    void oneLeadingWwwIsStripped() {
        assertThat(fold("https://www.example.com/feed")).isEqualTo(fold("https://example.com/feed"));
        assertThat(fold("https://www.www.example.com/feed")).isNotEqualTo(fold("https://example.com/feed"));
    }

    @Test
    void wwwInsideALongerLabelIsNotStripped() {
        assertThat(fold("https://awww.example.com/feed")).isNotEqualTo(fold("https://a.example.com/feed"));
    }

    @Test
    void defaultPortsAreDropped() {
        assertThat(fold("http://example.com:80/feed")).isEqualTo(fold("http://example.com/feed"));
        assertThat(fold("https://example.com:443/feed")).isEqualTo(fold("https://example.com/feed"));
    }

    @Test
    void otherPortsAreKept() {
        assertThat(fold("https://example.com:8443/feed")).isNotEqualTo(fold("https://example.com/feed"));
        assertThat(fold("https://example.com:80/feed")).isNotEqualTo(fold("https://example.com/feed"));
        assertThat(fold("http://example.com:443/feed")).isNotEqualTo(fold("http://example.com/feed"));
    }

    @Test
    void trailingSlashIsIgnoredAndAnEmptyPathEqualsSlash() {
        assertThat(fold("https://example.com/feed/")).isEqualTo(fold("https://example.com/feed"));
        assertThat(fold("https://example.com")).isEqualTo(fold("https://example.com/"));
    }

    @Test
    void queryIsKeptVerbatimAndInOrder() {
        assertThat(fold("https://example.com/feed?id=1")).isNotEqualTo(fold("https://example.com/feed?id=2"));
        assertThat(fold("https://example.com/feed?a=1&b=2")).isNotEqualTo(fold("https://example.com/feed?b=2&a=1"));
        assertThat(fold("https://example.com/feed?id=1")).isNotEqualTo(fold("https://example.com/feed"));
        assertThat(fold("https://www.example.com/feed/?id=1")).isEqualTo(fold("http://example.com/feed/?id=1"));
    }

    @Test
    void pathCaseIsKept() {
        assertThat(fold("https://example.com/Feed")).isNotEqualTo(fold("https://example.com/feed"));
    }

    @Test
    void fragmentIsIgnored() {
        assertThat(fold("https://example.com/feed#top")).isEqualTo(fold("https://example.com/feed"));
    }

    @Test
    void foldingEquivalentSpellingsGivesOneStableValue() {
        String once = fold("HTTP://WWW.Example.com:80/feed/?id=1#x");

        assertThat(once).isEqualTo("example.com/feed?id=1");
        assertThat(fold("https://example.com/feed?id=1")).isEqualTo(once);
    }
}
