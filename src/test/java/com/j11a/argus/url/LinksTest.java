package com.j11a.argus.url;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinksTest {

    @Test
    void cleanLowercasesSchemeAndHostAndDropsTheFragment() {
        assertThat(Links.clean("  HTTPS://News.Example.COM/Path/A?x=1#top "))
                .isEqualTo("https://news.example.com/Path/A?x=1");
    }

    @Test
    void cleanKeepsWwwParameterOrderTrackingParametersPortAndEscapes() {
        String link = "http://www.example.com:8080/a%2Fb?utm_source=x&b=2&a=1";

        assertThat(Links.clean(link)).isEqualTo(link);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "ftp://example.com/a", "file:///etc/passwd", "/relative/path", "mailto:a@b.c",
            "javascript:alert(1)", "https:///nohost", "http://bad host/", "not a url"})
    void cleanRejectsAnythingThatIsNotAnAbsoluteHttpUrl(String link) {
        assertThat(Links.clean(link)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP://Example.com/A#frag", "https://example.com", "https://[::1]:9000/x?y=%20z",
            "http://user:pw@Host.example/p"})
    void cleanIsIdempotent(String link) {
        String once = Links.clean(link);

        assertThat(once).isNotNull();
        assertThat(Links.clean(once)).isEqualTo(once);
    }
}
