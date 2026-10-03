package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContentHashTest {

    @Test
    void whitespaceNbspAndNfkcVariantsHashTheSame() {
        String base = ContentHash.of("Breaking News", "Some excerpt text", List.of("World"));
        String withNbsp = ContentHash.of("Breaking\u00A0News", "Some\u00A0excerpt text", List.of("World"));
        String withExtraWhitespace = ContentHash.of("  Breaking   \n\t News  ", "  Some   excerpt   text  ", List.of("World"));
        String nfkcVariant = ContentHash.of("Breaking News \uFB01le", "excerpt", List.of("World")); // ﬁ ligature
        String nfkcNormalized = ContentHash.of("Breaking News file", "excerpt", List.of("World"));

        assertThat(withNbsp).isEqualTo(base);
        assertThat(withExtraWhitespace).isEqualTo(base);
        assertThat(nfkcVariant).isEqualTo(nfkcNormalized);
    }

    @Test
    void categoryOrderDuplicatesAndCaseVariantsHashTheSame() {
        String base = ContentHash.of("Title", "Excerpt", List.of("News", "Sports"));
        String reordered = ContentHash.of("Title", "Excerpt", List.of("Sports", "News"));
        String duplicatesAndCaseVariants = ContentHash.of("Title", "Excerpt", List.of("News", "news", "NEWS", "Sports", "SPORTS"));
        String withBlanks = ContentHash.of("Title", "Excerpt", List.of("News", "  ", "", "Sports"));

        assertThat(reordered).isEqualTo(base);
        assertThat(duplicatesAndCaseVariants).isEqualTo(base);
        assertThat(withBlanks).isEqualTo(base);
    }

    @Test
    void caseChangeInTitleHashesDifferently() {
        String upper = ContentHash.of("Breaking News", "Excerpt", List.of());
        String lower = ContentHash.of("breaking news", "Excerpt", List.of());

        assertThat(upper).isNotEqualTo(lower);
    }

    @Test
    void nullExcerptEqualsEmptyString() {
        String withNull = ContentHash.of("Title", null, List.of("News"));
        String withEmpty = ContentHash.of("Title", "", List.of("News"));
        String withWhitespace = ContentHash.of("Title", "   \t\n  ", List.of("News"));

        assertThat(withNull).isEqualTo(withEmpty);
        assertThat(withWhitespace).isEqualTo(withEmpty);
    }
}
