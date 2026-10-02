package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParsedValuesTest {

    private static ParsedEntry entryWith(String title, String excerpt, List<String> categories) {
        return new ParsedEntry(null, null, title, excerpt, null, null, categories, null, null);
    }

    @Test
    void anEntryWithoutTitleOrExcerptGetsEmptyStrings() {
        ParsedEntry entry = entryWith(null, null, List.of());

        assertThat(entry.title()).isEmpty();
        assertThat(entry.excerpt()).isEmpty();
    }

    @Test
    void anEntryStripsItsTitleAndKeepsItsExcerpt() {
        ParsedEntry entry = entryWith("  Headline \n", " summary ", List.of());

        assertThat(entry.title()).isEqualTo("Headline");
        assertThat(entry.excerpt()).isEqualTo(" summary ");
    }

    @Test
    void anEntryCopiesItsCategoriesSoLaterChangesToTheSourceListDoNotLeakIn() {
        List<String> categories = new ArrayList<>(List.of("tech"));

        ParsedEntry entry = entryWith("t", "e", categories);
        categories.add("sport");

        assertThat(entry.categories()).containsExactly("tech");
    }

    @Test
    void aFeedWithoutATitleGetsAnEmptyOne() {
        ParsedFeed feed = new ParsedFeed(null, null, null, null, List.of());

        assertThat(feed.title()).isEmpty();
    }

    @Test
    void aFeedStripsItsTitleAndCopiesItsEntries() {
        List<ParsedEntry> entries = new ArrayList<>(List.of(entryWith("a", "b", List.of())));

        ParsedFeed feed = new ParsedFeed("  Daily  ", null, null, null, entries);
        entries.clear();

        assertThat(feed.title()).isEqualTo("Daily");
        assertThat(feed.entries()).hasSize(1);
    }
}
