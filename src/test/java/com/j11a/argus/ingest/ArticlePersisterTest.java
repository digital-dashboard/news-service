package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.article.ArticleInserter;
import com.j11a.argus.article.InsertOutcome;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.source.Source;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ArticlePersisterTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private final ArticleInserter inserter = mock(ArticleInserter.class);
    private final ArticlePersister persister = new ArticlePersister(inserter);
    private final Feed feed = mock(Feed.class);

    @BeforeEach
    void wire() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(2L);
        when(feed.getId()).thenReturn(1L);
        when(feed.getSource()).thenReturn(source);
    }

    private static ParsedEntry entry(String guid, String link) {
        return new ParsedEntry(guid, link, "t", "", null, null, List.of(), null, null);
    }

    @Test
    void entriesAreInsertedInAscendingGuidKeyOrderWhateverTheFeedOrder() {
        when(inserter.insert(any(), anyLong())).thenReturn(InsertOutcome.INSERTED);

        persister.persist(feed, List.of(entry("c", null), entry("a", null), entry("b", null)), FETCHED_AT);

        ArgumentCaptor<NewArticle> articles = ArgumentCaptor.forClass(NewArticle.class);
        verify(inserter, org.mockito.Mockito.times(3)).insert(articles.capture(), anyLong());
        assertThat(articles.getAllValues()).extracting(NewArticle::guidKey).containsExactly("a", "b", "c");
    }

    @Test
    void countsAreUnchangedAndEntriesWithoutAKeyAreSkippedWhereverTheyAre() {
        when(inserter.insert(any(), anyLong())).thenReturn(InsertOutcome.INSERTED, InsertOutcome.UNCHANGED);

        PersistCounts counts = persister.persist(feed,
                List.of(entry("b", null), entry(null, null), entry("a", null)), FETCHED_AT);

        assertThat(counts.inserted()).isEqualTo(1);
        assertThat(counts.unchanged()).isEqualTo(1);
        assertThat(counts.skipped()).isEqualTo(Map.of(ArticlePersister.MISSING_IDENTITY, 1));
    }
}
