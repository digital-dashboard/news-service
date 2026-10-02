package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExcerptBuilderTest {

    private static final int MAX = ExcerptBuilder.MAX_EXCERPT_LENGTH;

    @Test
    void stripsTagsDecodesEntitiesAndCollapsesWhitespace() {
        String excerpt = ExcerptBuilder.fromHtml("<p>Fish &amp;   chips</p>\n\n<p>cost&nbsp;&pound;5 &mdash; <b>cheap</b></p>");

        assertThat(excerpt).isEqualTo("Fish & chips cost £5 — cheap");
    }

    @Test
    void dropsScriptAndStyleContent() {
        String excerpt = ExcerptBuilder.fromHtml("<style>p{color:red}</style><p>Visible</p><script>evil()</script>");

        assertThat(excerpt).isEqualTo("Visible");
    }

    @Test
    void nestedTagsKeepWordsApart() {
        assertThat(ExcerptBuilder.fromHtml("<ul><li>one</li><li>two</li></ul>")).isEqualTo("one two");
    }

    @Test
    void nullAndBlankGiveEmpty() {
        assertThat(ExcerptBuilder.fromHtml(null)).isEmpty();
        assertThat(ExcerptBuilder.fromHtml("  \n ")).isEmpty();
        assertThat(ExcerptBuilder.fromHtml("<p> </p>")).isEmpty();
    }

    @Test
    void textAtTheLimitIsNotTruncated() {
        String text = "a".repeat(MAX);

        assertThat(ExcerptBuilder.fromHtml(text)).isEqualTo(text);
    }

    @Test
    void longTextIsCutAtAWordBoundaryWithAnEllipsis() {
        String text = "word ".repeat(200);

        String excerpt = ExcerptBuilder.fromHtml(text);

        assertThat(excerpt).endsWith("word…");
        assertThat(excerpt.length()).isLessThanOrEqualTo(MAX + 1);
        assertThat(excerpt).doesNotContain("  ");
    }

    @Test
    void aWordCrossingTheLimitIsDroppedWhole() {
        String text = "x".repeat(MAX - 4) + " crossing the limit";

        String excerpt = ExcerptBuilder.fromHtml(text);

        assertThat(excerpt).isEqualTo("x".repeat(MAX - 4) + "…");
    }

    @Test
    void aSingleOverlongWordIsHardCut() {
        String excerpt = ExcerptBuilder.fromHtml("y".repeat(MAX * 2));

        assertThat(excerpt).isEqualTo("y".repeat(MAX) + "…");
    }

    @Test
    void hardCutDoesNotSplitASurrogatePair() {
        String text = "y".repeat(MAX - 1) + "😀".repeat(5);

        String excerpt = ExcerptBuilder.fromHtml(text);

        assertThat(excerpt).isEqualTo("y".repeat(MAX - 1) + "…");
    }
}
