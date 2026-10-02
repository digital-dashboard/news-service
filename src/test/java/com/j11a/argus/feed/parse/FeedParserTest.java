package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.parse.FeedParseException.Reason;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.FeedStubServer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeedParserTest {

    private static final URI FEED_URL = URI.create("https://feeds.example.test/dir/feed.xml");

    private final FeedParser parser = new FeedParser();

    private ParsedFeed parse(String fixture) throws FeedParseException {
        return parser.parse(Fixtures.feed(fixture), FEED_URL, null);
    }

    @Test
    void parsesRss2WithMediaThumbnailAndFeedMetadata() throws Exception {
        ParsedFeed feed = parse("bbc-like-rss2.xml");

        assertThat(feed.title()).isEqualTo("Harbour Times - World");
        assertThat(feed.siteLink()).isEqualTo("https://news.example.test/world");
        assertThat(feed.selfLink()).isEqualTo("https://feeds.example.test/world/rss.xml");
        assertThat(feed.language()).isEqualTo("en-gb");
        assertThat(feed.entries()).hasSize(2);
        ParsedEntry first = feed.entries().get(0);
        assertThat(first.title()).isEqualTo("Ferry service resumes after storm");
        assertThat(first.guid()).isEqualTo("https://news.example.test/world/articles/c1abc#0");
        assertThat(first.link()).isEqualTo("https://news.example.test/world/articles/c1abc?at_medium=RSS&at_campaign=rss");
        assertThat(first.excerpt()).isEqualTo("Crossings restart on Wednesday morning after two days of cancellations.");
        assertThat(first.imageUrl()).isEqualTo("https://img.example.test/c1abc/240.jpg");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-06T07:30:00Z"));
        assertThat(first.updatedAt()).isNull();
    }

    @Test
    void wordpressEntryUsesDcCreatorAsAuthorAndReadsCategories() throws Exception {
        ParsedFeed feed = parse("wordpress-rss2.xml");

        ParsedEntry entry = feed.entries().get(0);
        assertThat(entry.author()).isEqualTo("Naledi Phiri");
        assertThat(entry.categories()).containsExactly("Football", "Match report");
        assertThat(entry.guid()).isEqualTo("https://blog.example.test/?p=1201");
        assertThat(feed.selfLink()).isEqualTo("https://blog.example.test/feed/");
        assertThat(feed.language()).isEqualTo("en-US");
    }

    @Test
    void excerptFallsBackToContentEncodedWhenTheDescriptionIsEmpty() throws Exception {
        ParsedFeed feed = parse("wordpress-rss2.xml");

        assertThat(feed.entries().get(1).excerpt())
                .isEqualTo("Only the content element carries text here: three signings, one departure.");
    }

    @Test
    void atomKeepsPublishedAndUpdatedSeparately() throws Exception {
        ParsedFeed feed = parse("atom10.xml");

        ParsedEntry both = feed.entries().get(0);
        assertThat(both.publishedAt()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(both.updatedAt()).isEqualTo(Instant.parse("2026-10-02T09:30:00Z"));
        ParsedEntry updatedOnly = feed.entries().get(1);
        assertThat(updatedOnly.publishedAt()).isNull();
        assertThat(updatedOnly.updatedAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
    }

    @Test
    void atomAuthorFallsBackFromEntryToFeed() throws Exception {
        ParsedFeed feed = parse("atom10.xml");

        assertThat(feed.entries().get(0).author()).isEqualTo("Amélie Roy");
        assertThat(feed.entries().get(1).author()).isEqualTo("Redaction du port");
    }

    @Test
    void atomExposesSiteLinkSelfLinkGuidAndCategories() throws Exception {
        ParsedFeed feed = parse("atom10.xml");

        assertThat(feed.title()).isEqualTo("Journal du port");
        assertThat(feed.siteLink()).isEqualTo("https://port.example.test/");
        assertThat(feed.selfLink()).isEqualTo("https://port.example.test/atom.xml");
        ParsedEntry entry = feed.entries().get(0);
        assertThat(entry.guid()).isEqualTo("tag:port.example.test,2026:entry-1");
        assertThat(entry.link()).isEqualTo("https://port.example.test/marche-reouvre");
        assertThat(entry.categories()).containsExactly("Economie", "Local");
        assertThat(entry.excerpt()).isEqualTo("Le marche couvert reouvre ses portes.");
    }

    @Test
    void parsesAtom03() throws Exception {
        ParsedFeed feed = parse("atom03.xml");

        assertThat(feed.title()).isEqualTo("Old Atom Notes");
        ParsedEntry entry = feed.entries().get(0);
        assertThat(entry.link()).isEqualTo("https://old.example.test/note-1");
        assertThat(entry.excerpt()).isEqualTo("Atom 0.3 still exists in the wild.");
        assertThat(entry.author()).isEqualTo("Pat Lee");
    }

    @Test
    void parsesRss10Rdf() throws Exception {
        ParsedFeed feed = parse("rss10-rdf.xml");

        assertThat(feed.title()).isEqualTo("Science Wire");
        assertThat(feed.language()).isEqualTo("en");
        ParsedEntry entry = feed.entries().get(0);
        assertThat(entry.title()).isEqualTo("New coral survey published");
        assertThat(entry.author()).isEqualTo("Dr. Ines Costa");
        assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-09-28T14:00:00Z"));
        assertThat(entry.categories()).containsExactly("Marine biology");
    }

    @Test
    void parsesRss091WithoutDoctype() throws Exception {
        ParsedFeed feed = parse("rss091.xml");

        assertThat(feed.title()).isEqualTo("Plain Old Feed");
        assertThat(feed.entries()).hasSize(1);
        assertThat(feed.entries().get(0).link()).isEqualTo("https://plain.example.test/one");
    }

    @Test
    void relativeLinksResolveAgainstTheSiteLink() throws Exception {
        ParsedFeed feed = parse("relative-links.xml");

        assertThat(feed.entries().get(0).link()).isEqualTo("https://rel.example.test/stories/42");
        assertThat(feed.entries().get(1).link()).isEqualTo("https://rel.example.test/news/stories/43");
        assertThat(feed.entries().get(0).imageUrl()).isEqualTo("https://rel.example.test/img/42.jpg");
    }

    @Test
    void entryLinksThatAreNotHttpAreDroppedButTheEntryKeepsItsGuid() throws Exception {
        ParsedFeed feed = parse("unsafe-link.xml");

        assertThat(feed.entries()).extracting(ParsedEntry::link).containsOnlyNulls();
        assertThat(feed.entries()).extracting(ParsedEntry::guid).containsExactly("unsafe-1", "unsafe-2");
    }

    @Test
    void relativeLinksResolveAgainstTheFeedUrlWhenTheFeedHasNoSiteLink() throws Exception {
        String xml = "<rss version=\"2.0\"><channel><title>t</title><description>d</description>"
                + "<item><title>a</title><link>/x/1</link></item></channel></rss>";

        ParsedFeed feed = parser.parse(xml.getBytes(StandardCharsets.UTF_8), FEED_URL, null);

        assertThat(feed.entries().get(0).link()).isEqualTo("https://feeds.example.test/x/1");
    }

    @Test
    void missingFieldsStayNullAndBlankTitleBecomesEmpty() throws Exception {
        ParsedFeed feed = parse("missing-guid-date.xml");

        ParsedEntry linkOnly = feed.entries().get(0);
        assertThat(linkOnly.title()).isEmpty();
        assertThat(linkOnly.link()).isEqualTo("https://sparse.example.test/a");
        assertThat(linkOnly.publishedAt()).isNull();
        assertThat(linkOnly.updatedAt()).isNull();
        assertThat(linkOnly.author()).isNull();
        assertThat(linkOnly.imageUrl()).isNull();
        assertThat(linkOnly.categories()).isEmpty();
    }

    @Test
    void entryWithoutLinkOrGuidIsStillReturnedWithNullIdentity() throws Exception {
        ParsedFeed feed = parse("missing-guid-date.xml");

        ParsedEntry anonymous = feed.entries().get(1);
        assertThat(anonymous.guid()).isNull();
        assertThat(anonymous.link()).isNull();
        assertThat(anonymous.title()).isEqualTo("No identity at all");
    }

    @Test
    void guidIsTrimmed() throws Exception {
        ParsedFeed feed = parse("missing-guid-date.xml");

        assertThat(feed.entries().get(2).guid()).isEqualTo("urn:sparse:3");
    }

    @Test
    void missingGuidIsNotReportedAsTheLink() throws Exception {
        ParsedFeed feed = parse("missing-guid-date.xml");

        assertThat(feed.entries().get(0).guid()).isNull();
    }

    @Test
    void htmlHeavyDescriptionBecomesBoundedPlainText() throws Exception {
        ParsedFeed feed = parse("html-heavy.xml");

        String excerpt = feed.entries().get(0).excerpt();
        assertThat(excerpt).startsWith("Council & residents met on Monday to discuss the harbour plan — a proposal");
        assertThat(excerpt).contains("Cost: £4m Timeline: three years");
        assertThat(excerpt).doesNotContain("<p>").doesNotContain("<div").doesNotContain("alert(");
        assertThat(excerpt.length()).isLessThanOrEqualTo(ExcerptBuilder.MAX_EXCERPT_LENGTH + 1);
        assertThat(excerpt).endsWith("…");
    }

    @Test
    void wrongEncodingDeclarationStillGivesReadableText() throws Exception {
        // The fixture is stored as UTF-8 text; the bytes are re-encoded here while the declaration still says UTF-8.
        byte[] windows1252Bytes = new String(Fixtures.feed("wrong-encoding.xml"), StandardCharsets.UTF_8)
                .getBytes(Charset.forName("windows-1252"));

        ParsedFeed feed = parser.parse(windows1252Bytes, FEED_URL, null);

        assertThat(feed.title()).isEqualTo("Café Dispatch");
        assertThat(feed.entries().get(0).title()).isEqualTo("The café reopens – “at last”");
        assertThat(feed.entries().get(0).excerpt()).isEqualTo("Fresh bread and a curly ’ quote.");
    }

    @Test
    void utf8ByteOrderMarkBeforeAWindows1252FallbackStillParses() throws Exception {
        byte[] windows1252Bytes = new String(Fixtures.feed("wrong-encoding.xml"), StandardCharsets.UTF_8)
                .getBytes(Charset.forName("windows-1252"));
        byte[] withBom = new byte[windows1252Bytes.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(windows1252Bytes, 0, withBom, 3, windows1252Bytes.length);

        ParsedFeed feed = parser.parse(withBom, FEED_URL, null);

        assertThat(feed.title()).isEqualTo("Café Dispatch");
    }

    @Test
    void httpCharsetInTheContentTypeIsHonoured() throws Exception {
        String xml = "<rss version=\"2.0\"><channel><title>Łódź Dziennik</title><description>d</description>"
                + "<item><title>Łódź</title><link>https://pl.example.test/1</link></item></channel></rss>";
        byte[] body = xml.getBytes(Charset.forName("ISO-8859-2"));

        ParsedFeed feed = parser.parse(body, FEED_URL, "application/xml; charset=ISO-8859-2");

        assertThat(feed.title()).isEqualTo("Łódź Dziennik");
        assertThat(feed.entries().get(0).title()).isEqualTo("Łódź");
    }

    @Test
    void emptyBodyIsReportedAsEmpty() {
        assertThatThrownBy(() -> parse("empty.xml"))
                .isInstanceOfSatisfying(FeedParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
    }

    @Test
    void whitespaceOnlyBodyIsReportedAsEmpty() {
        assertThatThrownBy(() -> parser.parse(" \n\t ".getBytes(), FEED_URL, null))
                .isInstanceOfSatisfying(FeedParseException.class, e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
    }

    @Test
    void malformedXmlIsReportedAsMalformed() {
        assertThatThrownBy(() -> parse("malformed.xml"))
                .isInstanceOfSatisfying(FeedParseException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED_XML));
    }

    @Test
    void wellFormedNonFeedXmlIsReportedAsNotAFeed() {
        assertThatThrownBy(() -> parse("not-a-feed.html"))
                .isInstanceOfSatisfying(FeedParseException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_FEED));
    }

    @Test
    void htmlThatIsNotWellFormedXmlIsReportedAsNotAFeed() {
        assertThatThrownBy(() -> parse("not-a-feed-html5.html"))
                .isInstanceOfSatisfying(FeedParseException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_FEED));
    }

    @Test
    void parseFailureMessageNeverContainsUpstreamText() {
        assertThatThrownBy(() -> parse("malformed.xml"))
                .hasMessageNotContaining("Broken feed")
                .hasMessageNotContaining("Unclosed");
        assertThatThrownBy(() -> parse("not-a-feed.html"))
                .hasMessageNotContaining("ordinary page");
    }

    @Test
    void binaryGarbageIsATypedFailure() {
        byte[] garbage = {0x00, 0x01, (byte) 0xFF, (byte) 0xFE, 0x42, 0x13, 0x37};

        assertThatThrownBy(() -> parser.parse(garbage, FEED_URL, "application/rss+xml"))
                .isInstanceOf(FeedParseException.class);
    }

    @Test
    void doctypeAllowsRss091WithTheNetscapeDtd() throws Exception {
        ParsedFeed feed = parse("rss091-doctype.xml");

        assertThat(feed.title()).isEqualTo("Plain Old Feed");
        assertThat(feed.entries()).hasSize(1);
    }

    @Test
    void externalEntityNeverLoadsLocalFileContent() throws Exception {
        ParsedFeed feed = parse("xxe.xml");

        assertThat(feed.toString()).doesNotContain("root:").doesNotContain("/bin/").doesNotContain("nobody");
    }

    @Test
    void externalEntityAndDtdNeverReachTheNetwork() throws Exception {
        try (FeedStubServer server = new FeedStubServer()) {
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE rss SYSTEM \"" + server.baseUrl() + "/evil.dtd\" ["
                    + "<!ENTITY ext SYSTEM \"" + server.baseUrl() + "/entity\">]>"
                    + "<rss version=\"2.0\"><channel><title>&ext;</title><description>d</description>"
                    + "<item><title>&ext;</title><link>https://x.example.test/1</link></item></channel></rss>";

            parser.parse(xml.getBytes(StandardCharsets.UTF_8), FEED_URL, null);

            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void billionLaughsFailsFastWithATypedException() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> parse("billion-laughs.xml"))
                .isInstanceOfSatisfying(FeedParseException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED_XML));

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
    }
}
