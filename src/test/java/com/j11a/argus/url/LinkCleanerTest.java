package com.j11a.argus.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCleanerTest {

    @ParameterizedTest
    @CsvSource({
            "http://example.com/a, https://example.com/a",
            "HTTP://example.com/a, https://example.com/a",
            "https://example.com/a, https://example.com/a"
    })
    void schemeFoldHttpToHttps(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://EXAMPLE.COM/a, https://example.com/a",
            "https://ExAmPLe.CoM/A/B, https://example.com/A/B"
    })
    void hostLowercase(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.example.com/a, https://example.com/a",
            "https://m.example.com/a, https://example.com/a",
            "https://amp.example.com/a, https://example.com/a",
            "https://www.m.x.com/a, https://x.com/a",
            "https://amp.m.example.com/a, https://example.com/a",
            "https://www.co.uk/a, https://www.co.uk/a",
            "https://www.com/a, https://www.com/a",
            "https://m.com/a, https://m.com/a",
            "https://amp.com/a, https://amp.com/a"
    })
    void prefixStrippingAndRemainderMustContainDotGuard(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://user:pass@example.com/a, https://example.com/a",
            "https://user@example.com/a, https://example.com/a"
    })
    void userInfoDropped(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "http://example.com:80/a, https://example.com/a",
            "https://example.com:443/a, https://example.com/a",
            "https://example.com:8080/a, https://example.com:8080/a",
            "http://example.com:8443/a, https://example.com:8443/a"
    })
    void ports80And443DroppedAnd8080Kept(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/, https://example.com",
            "https://example.com/a/, https://example.com/a",
            "https://example.com/a/amp, https://example.com/a",
            "https://example.com/a/amp/, https://example.com/a",
            "https://example.com/a/AMP, https://example.com/a",
            "https://example.com/a/AMP/, https://example.com/a",
            "https://example.com/amp, https://example.com",
            "https://example.com/amp/, https://example.com",
            "https://example.com/a/amp/amp/, https://example.com/a"
    })
    void trailingSlashAndAmpStripped(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/a?fbclid=123, https://example.com/a",
            "https://example.com/a?gclid=456, https://example.com/a",
            "https://example.com/a?mc_cid=abc, https://example.com/a",
            "https://example.com/a?mc_eid=def, https://example.com/a",
            "https://example.com/a?cmpid=ghi, https://example.com/a",
            "https://example.com/a?ref=jkl, https://example.com/a",
            "https://example.com/a?utm_source=twitter, https://example.com/a",
            "https://example.com/a?utm_campaign=winter, https://example.com/a",
            "https://example.com/a?at_medium=custom7, https://example.com/a",
            "https://example.com/a?UTM_SOURCE=promo, https://example.com/a",
            "https://example.com/a?utm%5Fsource=encoded, https://example.com/a",
            "https://example.com/a?FBCLID=XYZ, https://example.com/a"
    })
    void trackingParamsDropped(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/a?amp=1, https://example.com/a",
            "https://example.com/a?outputType=amp, https://example.com/a",
            "https://example.com/a?OUTPUTTYPE=AMP, https://example.com/a",
            "https://example.com/a?outputType=xml, https://example.com/a?outputType=xml",
            "https://example.com/a?p=123, https://example.com/a?p=123",
            "https://example.com/a?amp=2, https://example.com/a?amp=2"
    })
    void ampParamsAndOtherParams(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/a?z=1&a=2&m=3, https://example.com/a?a=2&m=3&z=1",
            "https://example.com/a?tag=b&tag=a, https://example.com/a?tag=a&tag=b",
            "https://example.com/a?novalue&foo=bar, https://example.com/a?foo=bar&novalue",
            "https://example.com/a?b=&a=, https://example.com/a?a=&b=",
            "https://example.com/a?a=1&&b=2, https://example.com/a?a=1&b=2"
    })
    void querySortingDuplicatesAndValuelessParams(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/a#section, https://example.com/a",
            "https://example.com/a?p=1#top, https://example.com/a?p=1"
    })
    void fragmentDropped(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/Foo%20Bar/Baz, https://example.com/Foo%20Bar/Baz",
            "https://example.com/A/B/C, https://example.com/A/B/C"
    })
    void escapesAndPathCasePreserved(String input, String expected) {
        assertThat(LinkCleaner.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/a",
            "mailto:user@example.com",
            "/relative/path",
            "not-a-url",
            "",
            "   "
    })
    void nonHttpRelativeAndBlankGiveNull(String input) {
        assertThat(LinkCleaner.clean(input)).isNull();
    }

    @Test
    void nullLinkCleansToNull() {
        assertThat(LinkCleaner.clean(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "https://bbc.co.uk, true",
            "https://bbc.co.uk:8080, true",
            "https://bbc.co.uk/, false",
            "https://bbc.co.uk/a, false",
            "https://bbc.co.uk?p=1, false",
            "https://bbc.co.uk/a?p=1, false",
            "not-a-url, false"
    })
    void isRootCases(String link, boolean expected) {
        assertThat(LinkCleaner.isRoot(link)).isEqualTo(expected);
    }

    @Test
    void isRootNullIsFalse() {
        assertThat(LinkCleaner.isRoot(null)).isFalse();
    }

    @Test
    void idempotenceCorpusAndRandomGenerator() {
        List<String> corpus = List.of(
                "http://example.com/a",
                "HTTP://example.com/a",
                "https://example.com/a",
                "https://EXAMPLE.COM/a",
                "https://ExAmPLe.CoM/A/B",
                "https://www.example.com/a",
                "https://m.example.com/a",
                "https://amp.example.com/a",
                "https://www.m.x.com/a",
                "https://amp.m.example.com/a",
                "https://www.co.uk/a",
                "https://www.com/a",
                "https://m.com/a",
                "https://amp.com/a",
                "https://user:pass@example.com/a",
                "https://user@example.com/a",
                "http://example.com:80/a",
                "https://example.com:443/a",
                "https://example.com:8080/a",
                "http://example.com:8443/a",
                "https://example.com/",
                "https://example.com/a/",
                "https://example.com/a/amp",
                "https://example.com/a/amp/",
                "https://example.com/a/AMP",
                "https://example.com/a/AMP/",
                "https://example.com/amp",
                "https://example.com/amp/",
                "https://example.com/a/amp/amp/",
                "https://example.com/a?fbclid=123",
                "https://example.com/a?gclid=456",
                "https://example.com/a?mc_cid=abc",
                "https://example.com/a?mc_eid=def",
                "https://example.com/a?cmpid=ghi",
                "https://example.com/a?ref=jkl",
                "https://example.com/a?utm_source=twitter",
                "https://example.com/a?utm_campaign=winter",
                "https://example.com/a?at_medium=custom7",
                "https://example.com/a?UTM_SOURCE=promo",
                "https://example.com/a?utm%5Fsource=encoded",
                "https://example.com/a?FBCLID=XYZ",
                "https://example.com/a?amp=1",
                "https://example.com/a?outputType=amp",
                "https://example.com/a?OUTPUTTYPE=AMP",
                "https://example.com/a?outputType=xml",
                "https://example.com/a?p=123",
                "https://example.com/a?amp=2",
                "https://example.com/a?z=1&a=2&m=3",
                "https://example.com/a?tag=b&tag=a",
                "https://example.com/a?novalue&foo=bar",
                "https://example.com/a?b=&a=",
                "https://example.com/a?a=1&&b=2",
                "https://example.com/a#section",
                "https://example.com/a?p=1#top",
                "https://example.com/Foo%20Bar/Baz",
                "https://example.com/A/B/C"
        );

        for (String url : corpus) {
            String cleaned = LinkCleaner.clean(url);
            assertThat(cleaned).isNotNull();
            assertThat(LinkCleaner.clean(cleaned)).isEqualTo(cleaned);
        }

        Random random = new Random(42);
        String[] schemes = {"http://", "https://", "HTTP://", "HTTPS://"};
        String[] userInfos = {"", "user@", "user:pass@"};
        String[] hosts = {"example.com", "www.example.com", "m.example.com", "amp.example.com", "www.m.x.com", "www.co.uk", "www.com", "EXAMPLE.ORG"};
        String[] ports = {"", ":80", ":443", ":8080", ":9000"};
        String[] paths = {"", "/", "/a", "/a/", "/a/amp", "/a/amp/", "/a/b/c", "/path%20with%20spaces/item"};
        String[] queryFragments = {"", "p=123", "utm_source=test", "amp=1", "outputType=amp", "outputType=xml", "z=9&a=1", "ref=home", "fbclid=abc", "valueless", "tag=1&tag=2"};
        String[] fragments = {"", "#top", "#section-1"};

        for (int i = 0; i < 1000; i++) {
            String url = schemes[random.nextInt(schemes.length)]
                    + userInfos[random.nextInt(userInfos.length)]
                    + hosts[random.nextInt(hosts.length)]
                    + ports[random.nextInt(ports.length)]
                    + paths[random.nextInt(paths.length)]
                    + (queryFragments[random.nextInt(queryFragments.length)].isEmpty() ? "" : "?" + queryFragments[random.nextInt(queryFragments.length)])
                    + fragments[random.nextInt(fragments.length)];

            String cleaned = LinkCleaner.clean(url);
            if (cleaned != null) {
                assertThat(LinkCleaner.clean(cleaned)).isEqualTo(cleaned);
            }
        }
    }
}
