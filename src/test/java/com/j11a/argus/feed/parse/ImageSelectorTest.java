package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.testsupport.Fixtures;
import java.net.URI;
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
}
