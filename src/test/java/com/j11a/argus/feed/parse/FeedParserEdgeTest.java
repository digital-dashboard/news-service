package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.parse.FeedParseException.Reason;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FeedParserEdgeTest {

    private static final URI FEED_URL = URI.create("https://feeds.example.test/feed.xml");

    private final FeedParser parser = new FeedParser();

    private static byte[] utf8(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static Reason reasonOf(Throwable failure) {
        return ((FeedParseException) failure).reason();
    }

    @Test
    void anAtomFeedLinkWithoutARelIsTheSiteLink() throws Exception {
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title><id>x</id>"
                + "<link href=\"https://port.example.test/\"/></feed>";

        ParsedFeed feed = parser.parse(utf8(atom), FEED_URL, null);

        assertThat(feed.siteLink()).isEqualTo("https://port.example.test/");
    }

    @Test
    void anAtomFeedLinkWithAnExplicitAlternateRelIsTheSiteLink() throws Exception {
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title><id>x</id>"
                + "<link rel=\"ALTERNATE\" href=\"https://port.example.test/\"/></feed>";

        ParsedFeed feed = parser.parse(utf8(atom), FEED_URL, null);

        assertThat(feed.siteLink()).isEqualTo("https://port.example.test/");
    }

    @Test
    void htmlWithoutADoctypeThatIsNotWellFormedIsNotAFeed() {
        byte[] html = utf8("<html><body><p>one<br>two</body></html>");

        assertThatThrownBy(() -> parser.parse(html, FEED_URL, "text/html"))
                .isInstanceOf(FeedParseException.class)
                .satisfies(failure -> assertThat(reasonOf(failure)).isEqualTo(Reason.NOT_A_FEED));
    }

    @Test
    void aTwoByteBodyThatIsNotUtf8IsATypedFailureNotACrash() {
        byte[] tiny = {'<', (byte) 0xE9};

        assertThatThrownBy(() -> parser.parse(tiny, FEED_URL, null)).isInstanceOf(FeedParseException.class);
    }

    @Test
    void anUnknownEncodingDeclarationIsMalformedXml() {
        byte[] body = utf8("<?xml version=\"1.0\" encoding=\"x-no-such-charset\"?><rss version=\"2.0\"/>");

        assertThatThrownBy(() -> parser.parse(body, FEED_URL, null))
                .isInstanceOf(FeedParseException.class)
                .satisfies(failure -> assertThat(reasonOf(failure)).isEqualTo(Reason.MALFORMED_XML));
    }
}
