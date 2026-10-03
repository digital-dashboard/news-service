package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SourceResolverTest {

    private static String keyFor(String siteLink, String feedUrl) {
        return SourceResolver.keyFor(siteLink, URI.create(feedUrl));
    }

    @Test
    void multiLabelPublicSuffixKeepsTheRegistrableDomain() {
        assertThat(keyFor(null, "https://feeds.bbci.co.uk/news/rss.xml")).isEqualTo("bbci.co.uk");
    }

    @Test
    void wwwPrefixIsDropped() {
        assertThat(keyFor(null, "https://www.cbc.ca/rss")).isEqualTo("cbc.ca");
    }

    @Test
    void secondLevelPublicSuffixOnCountryDomainIsHandled() {
        assertThat(keyFor(null, "https://www.zambianfootball.co.zm/feed")).isEqualTo("zambianfootball.co.zm");
    }

    @Test
    void multiLabelDomainWithSecondLevelCountrySuffix() {
        assertThat(keyFor(null, "https://x.y.com.au/feed")).isEqualTo("y.com.au");
    }

    @Test
    void privateDomainUnderPublicSuffixKeepsPrivateDomain() {
        assertThat(keyFor(null, "https://foo.blogspot.com/feed")).isEqualTo("foo.blogspot.com");
    }

    @Test
    void siteLinkHostIsPreferredOverTheFeedHost() {
        assertThat(keyFor("https://www.example.org/", "https://feeds.feedburner.com/x")).isEqualTo("example.org");
    }

    @Test
    void feedHostIsUsedWhenTheSiteLinkIsBlankRelativeOrNotHttp() {
        assertThat(keyFor("  ", "https://a.news.example.com/f")).isEqualTo("example.com");
        assertThat(keyFor("/relative", "https://a.news.example.com/f")).isEqualTo("example.com");
        assertThat(keyFor("ftp://other.example.net/", "https://a.news.example.com/f")).isEqualTo("example.com");
        assertThat(keyFor("http://bad host/", "https://a.news.example.com/f")).isEqualTo("example.com");
    }

    @Test
    void hostIsLowercasedAndTrailingDotStripped() {
        assertThat(keyFor(null, "https://WWW.Example.COM./feed")).isEqualTo("example.com");
    }

    @Test
    void ipv4AddressIsUsedAsIs() {
        assertThat(keyFor(null, "http://192.168.1.20:8080/rss")).isEqualTo("192.168.1.20");
    }

    @Test
    void ipv6AddressIsUsedAsIs() {
        assertThat(keyFor(null, "http://[::1]:8080/rss")).isEqualTo("[::1]");
    }

    @Test
    void localhostAndSingleLabelHostsAreUsedAsIs() {
        assertThat(keyFor(null, "http://localhost:9000/rss")).isEqualTo("localhost");
        assertThat(keyFor(null, "http://nas/rss")).isEqualTo("nas");
        assertThat(keyFor(null, "http://nas.local/rss")).isEqualTo("nas.local");
    }

    @Test
    void hostThatIsItselfAPublicSuffixIsUsedAsIs() {
        assertThat(keyFor(null, "https://co.uk/rss")).isEqualTo("co.uk");
    }

    @Test
    void hostlessFeedUrlGivesAnEmptyKey() {
        assertThat(SourceResolver.keyFor(null, URI.create("file:///tmp/x"))).isEmpty();
    }

    @Test
    void anOverlongHostIsCutToTheColumnWidth() {
        String host = "a".repeat(300) + ".example.test";

        assertThat(keyFor(null, "https://" + host + "/f")).hasSize(SourceResolver.MAX_KEY_LENGTH);
        assertThat(keyFor("https://" + host + "/", "https://feeds.example.test/f"))
                .hasSize(SourceResolver.MAX_KEY_LENGTH);
    }

    @Test
    void hostKeyIsTheRegistrableDomainOfTheUriHost() {
        assertThat(SourceResolver.hostKey(URI.create("https://feeds.bbci.co.uk/news/rss.xml"))).isEqualTo("bbci.co.uk");
        assertThat(SourceResolver.hostKey(URI.create("https://WWW.Example.COM./feed"))).isEqualTo("example.com");
    }

    @Test
    void keyOfLinkIsTheRegistrableDomainOfAnHttpLinkAndNullOtherwise() {
        assertThat(SourceResolver.keyOfLink("https://www.bbc.co.uk/news")).isEqualTo("bbc.co.uk");
        assertThat(SourceResolver.keyOfLink(null)).isNull();
        assertThat(SourceResolver.keyOfLink("/relative")).isNull();
        assertThat(SourceResolver.keyOfLink("ftp://example.com/")).isNull();
    }

    @Test
    void keyForStillPrefersTheSiteLinkAndFallsBackToTheHostKey() {
        assertThat(keyFor("https://www.bbc.co.uk/", "https://feeds.bbci.co.uk/x")).isEqualTo("bbc.co.uk");
        assertThat(keyFor(null, "https://feeds.bbci.co.uk/x")).isEqualTo("bbci.co.uk");
    }
}
