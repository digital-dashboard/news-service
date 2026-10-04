package com.j11a.argus.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RssBodyTest {

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void theSelfLinkOfAnRssFeedIsReplacedAndTheSiteLinkIsKept() {
        byte[] feed = Fixtures.feed("bbc-like-rss2.xml");

        String replaced = text(RssBody.withSelfLink(feed, "http://stub/own.xml?a=1&b=2"));

        assertThat(replaced).contains("<atom:link href=\"http://stub/own.xml?a=1&b=2\" rel=\"self\"")
                .doesNotContain("https://feeds.example.test/world/rss.xml")
                .contains("<link>https://news.example.test/world</link>");
    }

    @Test
    void theSelfLinkOfAnAtomFeedIsReplaced() {
        String replaced = text(RssBody.withSelfLink(Fixtures.feed("atom10.xml"), "http://stub/atom.xml"));

        assertThat(replaced).contains("href=\"http://stub/atom.xml\" rel=\"self\"")
                .doesNotContain("https://port.example.test/atom.xml");
    }

    @Test
    void aFeedWithoutASelfLinkIsReturnedUnchanged() {
        byte[] feed = RssBody.rss("https://site.test/", null, "a");

        assertThat(RssBody.withSelfLink(feed, "http://stub/x.xml")).isEqualTo(feed);
    }

    @Test
    void theBuiltFeedCarriesItsSelfLinkAndItems() {
        String xml = text(RssBody.rss("https://site.test/", "http://stub/self.xml", "a", "b"));

        assertThat(xml).contains("<atom:link href=\"http://stub/self.xml\" rel=\"self\"")
                .contains("<guid isPermaLink=\"false\">a</guid>").contains("<guid isPermaLink=\"false\">b</guid>");
    }
}
