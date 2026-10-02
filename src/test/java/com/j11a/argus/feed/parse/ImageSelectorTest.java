package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.testsupport.Fixtures;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ImageSelectorTest {

    private static List<ParsedEntry> entries;

    @BeforeAll
    static void parseFixture() throws Exception {
        entries = new FeedParser()
                .parse(Fixtures.feed("image-priority.xml"), URI.create("https://pics.example.test/feed.xml"), null)
                .entries();
    }

    private static String imageOf(int entryNumber) {
        return entries.get(entryNumber - 1).imageUrl();
    }

    @Test
    void thumbnailBeatsEveryOtherSource() {
        assertThat(imageOf(1)).isEqualTo("https://pics.example.test/thumb1.jpg");
    }

    @Test
    void mediaContentWithImageMediumBeatsEnclosureAndHtml() {
        assertThat(imageOf(2)).isEqualTo("https://pics.example.test/content2.jpg");
    }

    @Test
    void mediaContentWithImageTypeIsUsedAndVideoIsIgnored() {
        assertThat(imageOf(3)).isEqualTo("https://pics.example.test/content3.png");
    }

    @Test
    void imageEnclosureBeatsHtml() {
        assertThat(imageOf(4)).isEqualTo("https://pics.example.test/enc4.jpg");
    }

    @Test
    void nonImageEnclosureIsSkippedAndTheHtmlImageIsResolvedToAbsolute() {
        assertThat(imageOf(5)).isEqualTo("https://pics.example.test/relative5.jpg");
    }

    @Test
    void dataUriIsSkippedInFavourOfTheNextHtmlImage() {
        assertThat(imageOf(6)).isEqualTo("https://pics.example.test/second6.jpg");
    }

    @Test
    void htmlImageInContentEncodedIsFound() {
        assertThat(imageOf(7)).isEqualTo("https://pics.example.test/body7.jpg");
    }

    @Test
    void noImageGivesNull() {
        assertThat(imageOf(8)).isNull();
    }

    @Test
    void nonHttpThumbnailFallsThroughToTheEnclosure() {
        assertThat(imageOf(9)).isEqualTo("https://pics.example.test/enc9.jpg");
    }

    private static String imageFor(String itemBody) throws Exception {
        String xml = "<?xml version=\"1.0\"?><rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\">"
                + "<channel><title>t</title><link>https://pics.example.test/</link><description>d</description>"
                + "<item><title>x</title><guid>g</guid>" + itemBody + "</item></channel></rss>";
        return new FeedParser().parse(xml.getBytes(StandardCharsets.UTF_8),
                URI.create("https://pics.example.test/feed.xml"), null).entries().get(0).imageUrl();
    }

    @Test
    void anImageInsideAMediaGroupIsUsed() throws Exception {
        String item = "<media:group><media:content url=\"https://pics.example.test/group.jpg\" medium=\"image\"/>"
                + "</media:group>";

        assertThat(imageFor(item)).isEqualTo("https://pics.example.test/group.jpg");
    }

    @Test
    void aThumbnailOnAMediaGroupContentIsUsedEvenWhenTheContentIsVideo() throws Exception {
        String item = "<media:group><media:content url=\"https://pics.example.test/clip.mp4\" medium=\"video\">"
                + "<media:thumbnail url=\"https://pics.example.test/poster.jpg\"/></media:content></media:group>";

        assertThat(imageFor(item)).isEqualTo("https://pics.example.test/poster.jpg");
    }

    @Test
    void mediaContentWithNeitherMediumNorTypeIsNotTakenForAnImage() throws Exception {
        String item = "<media:content url=\"https://pics.example.test/unknown.bin\"/>"
                + "<description><![CDATA[<img src=\"https://pics.example.test/html.jpg\"/>]]></description>";

        assertThat(imageFor(item)).isEqualTo("https://pics.example.test/html.jpg");
    }

    @Test
    void anEnclosureWithoutATypeIsNotTakenForAnImage() throws Exception {
        String item = "<enclosure url=\"https://pics.example.test/unknown.bin\" length=\"1\"/>";

        assertThat(imageFor(item)).isNull();
    }
}
