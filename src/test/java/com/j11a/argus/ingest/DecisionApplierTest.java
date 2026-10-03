package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.article.ArticleEdit;
import com.j11a.argus.article.ArticleWriter;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.dedup.EntryDecision;
import com.j11a.argus.ingest.dedup.KeyedEntry;
import com.j11a.argus.ingest.dedup.LinkFallback;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.ingest.dedup.SkipReason;
import com.j11a.argus.ingest.dedup.UpdateReason;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DecisionApplierTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final ArticleWriter writer = mock(ArticleWriter.class);
    private final DecisionApplier applier = new DecisionApplier(writer);

    private static KeyedEntry keyedEntry(String guidKey, String linkKey, String title) {
        ParsedEntry parsed = new ParsedEntry(guidKey, "https://example.test/" + guidKey, title, "excerpt",
                "Author", null, List.of("cat1"), NOW, NOW);
        return new KeyedEntry(parsed, guidKey, linkKey, "hash-" + guidKey, NOW, 0);
    }

    @Test
    void decisionsAreAppliedInAscendingGuidKeyOrderWhateverTheResolutionOrder() {
        when(writer.insert(any(), any())).thenReturn(new ArticleWriter.WriteResult(10L, true));

        KeyedEntry c = keyedEntry("c", "link-c", "C");
        KeyedEntry a = keyedEntry("a", "link-a", "A");
        KeyedEntry b = keyedEntry("b", "link-b", "B");

        Resolution r = new Resolution(List.of(
                new EntryDecision.Insert(c),
                new EntryDecision.Insert(a),
                new EntryDecision.Insert(b)),
                Map.of());

        applier.apply(1L, 2L, r, NOW, NOW);

        ArgumentCaptor<NewArticle> captor = ArgumentCaptor.forClass(NewArticle.class);
        verify(writer, times(3)).insert(captor.capture(), eq(NOW_UTC));
        assertThat(captor.getAllValues()).extracting(NewArticle::guidKey).containsExactly("a", "b", "c");
    }

    @Test
    void insertSuccessWritesArticleAndFeedLink() {
        KeyedEntry entry = keyedEntry("g1", "link-1", "Title");
        when(writer.insert(any(), any())).thenReturn(new ArticleWriter.WriteResult(101L, true));

        Resolution r = new Resolution(List.of(new EntryDecision.Insert(entry)), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.inserted()).isEqualTo(1);
        assertThat(counts.updated()).isEmpty();
        verify(writer).link(101L, 2L, "hash-g1", NOW_UTC);
    }

    @Test
    void insertConflictCountsAsUpdatedInsertConflictAndStillLinks() {
        KeyedEntry entry = keyedEntry("g1", "link-1", "Title");
        when(writer.insert(any(), any())).thenReturn(new ArticleWriter.WriteResult(101L, false));

        Resolution r = new Resolution(List.of(new EntryDecision.Insert(entry)), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.inserted()).isZero();
        assertThat(counts.updated()).containsEntry("insert_conflict", 1);
        verify(writer).link(101L, 2L, "hash-g1", NOW_UTC);
    }

    @Test
    void updateContentChangedRewritesContentAndReplacesGuidIfFlagged() {
        KeyedEntry entry = keyedEntry("g2-new", "link-2", "New Title");
        EntryDecision.Update decision = new EntryDecision.Update(entry, 201L, UpdateReason.CONTENT_CHANGED, true);

        Resolution r = new Resolution(List.of(decision), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.updated()).containsEntry("content_changed", 1);
        verify(writer).replaceGuid(201L, "g2-new", "g2-new", NOW_UTC);
        ArgumentCaptor<ArticleEdit> editCaptor = ArgumentCaptor.forClass(ArticleEdit.class);
        verify(writer).rewriteContent(editCaptor.capture(), eq(NOW_UTC));
        assertThat(editCaptor.getValue().id()).isEqualTo(201L);
        assertThat(editCaptor.getValue().title()).isEqualTo("New Title");
        verify(writer).link(201L, 2L, "hash-g2-new", NOW_UTC);
    }

    @Test
    void updateTimestampOnlyAdvancesTimestamps() {
        KeyedEntry entry = keyedEntry("g3", "link-3", "Same Title");
        EntryDecision.Update decision = new EntryDecision.Update(entry, 301L, UpdateReason.TIMESTAMP_ONLY, false);

        Resolution r = new Resolution(List.of(decision), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.updated()).containsEntry("timestamp_only", 1);
        verify(writer, never()).replaceGuid(anyLong(), any(), any(), any());
        verify(writer, never()).rewriteContent(any(), any());
        verify(writer).advanceTimestamps(eq(301L), eq(NOW), eq(NOW), eq(true), eq(NOW_UTC));
        verify(writer).link(301L, 2L, "hash-g3", NOW_UTC);
    }

    @Test
    void linkDecisionOnlyLinksAndReplacesGuidIfFlagged() {
        KeyedEntry entry = keyedEntry("g4", "link-4", "Title");
        EntryDecision.Link decision = new EntryDecision.Link(entry, 401L, true);

        Resolution r = new Resolution(List.of(decision), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.linked()).isEqualTo(1);
        verify(writer).replaceGuid(401L, "g4", "g4", NOW_UTC);
        verify(writer).link(401L, 2L, "hash-g4", NOW_UTC);
        verify(writer, never()).rewriteContent(any(), any());
        verify(writer, never()).advanceTimestamps(anyLong(), any(), any(), anyBoolean(), any());
    }

    @Test
    void unchangedDecisionLinksOnlyWhenBackfillRequested() {
        KeyedEntry entry1 = keyedEntry("g5", "link-5", "Title");
        KeyedEntry entry2 = keyedEntry("g6", "link-6", "Title");
        EntryDecision.Unchanged unchangedNoBackfill = new EntryDecision.Unchanged(entry1, 501L, false, false);
        EntryDecision.Unchanged unchangedWithBackfill = new EntryDecision.Unchanged(entry2, 502L, false, true);

        Resolution r = new Resolution(List.of(unchangedNoBackfill, unchangedWithBackfill), Map.of());
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.unchanged()).isEqualTo(2);
        verify(writer, never()).link(eq(501L), anyLong(), any(), any());
        verify(writer).link(502L, 2L, "hash-g6", NOW_UTC);
    }

    @Test
    void skipDecisionWritesNothing() {
        ParsedEntry parsed = new ParsedEntry(null, null, "No Identity", "", null, null, List.of(), null, null);
        EntryDecision.Skip skipMissing = new EntryDecision.Skip(parsed, SkipReason.MISSING_IDENTITY);
        EntryDecision.Skip skipBatchDup = new EntryDecision.Skip(parsed, SkipReason.BATCH_DUPLICATE);

        Resolution r = new Resolution(List.of(skipMissing, skipBatchDup), Map.of(
                LinkFallback.GUID_REPLACED, 0,
                LinkFallback.GUARDED_HOMEPAGE, 0,
                LinkFallback.GUARDED_SHARED, 0));
        PersistCounts counts = applier.apply(1L, 2L, r, NOW, NOW);

        assertThat(counts.skipped()).containsEntry("missing_identity", 1);
        assertThat(counts.skipped()).containsEntry("batch_duplicate", 1);
        assertThat(counts.skippedTotal()).isEqualTo(2);
        verify(writer, never()).insert(any(), any());
        verify(writer, never()).rewriteContent(any(), any());
        verify(writer, never()).link(anyLong(), anyLong(), any(), any());
    }
}
