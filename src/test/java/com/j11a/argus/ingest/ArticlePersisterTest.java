package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.dedup.DedupInput;
import com.j11a.argus.ingest.dedup.EntryDedupResolver;
import com.j11a.argus.ingest.dedup.ExistingArticleLookup;
import com.j11a.argus.ingest.dedup.LinkFallback;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.simple.JdbcClient;

class ArticlePersisterTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:05Z");

    private final SourceLock sourceLock = mock(SourceLock.class);
    private final ExistingArticleLoader loader = mock(ExistingArticleLoader.class);
    private final EntryDedupResolver resolver = mock(EntryDedupResolver.class);
    private final DecisionApplier applier = mock(DecisionApplier.class);
    private final IngestTelemetry telemetry = mock(IngestTelemetry.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ArticlePersister persister = new ArticlePersister(sourceLock, loader, resolver, applier, telemetry, jdbc,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Feed feed = mock(Feed.class);
    private final Source source = mock(Source.class);
    private final ExistingArticleLookup lookup = mock(ExistingArticleLookup.class);

    @BeforeEach
    void setUp() {
        when(source.getId()).thenReturn(2L);
        when(source.getKey()).thenReturn("example.test");
        when(feed.getId()).thenReturn(1L);
        when(feed.getSource()).thenReturn(source);
        when(loader.forFeed(2L, 1L)).thenReturn(lookup);

        when(telemetry.lockWait(anyString(), anyLong(), any())).thenAnswer(inv -> {
            Supplier<Duration> supplier = inv.getArgument(2);
            return supplier.get();
        });
        when(telemetry.span(anyString(), any(), any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(2);
            return supplier.get();
        });
    }

    private void stubHomepage(String url) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.query(String.class)).thenReturn(new MockMappedQuerySpec<>(url));
    }

    @Test
    void persistCoordinatesLockHomepageResolutionAndApplication() {
        stubHomepage("https://example.test/home");
        Resolution resolution = new Resolution(List.of(), Map.of(
                LinkFallback.GUID_REPLACED, 0,
                LinkFallback.GUARDED_HOMEPAGE, 0,
                LinkFallback.GUARDED_SHARED, 0));
        when(resolver.resolve(any())).thenReturn(resolution);
        PersistCounts expectedCounts = new PersistCounts(1, Map.of(), 0, 0, Map.of(), resolution.linkFallbacks());
        when(applier.apply(2L, 1L, resolution, FETCHED_AT, NOW)).thenReturn(expectedCounts);

        ParsedEntry entry = new ParsedEntry("g1", "https://example.test/1", "t", "e", null, null, List.of(), null, null);
        PersistCounts counts = persister.persist(feed, List.of(entry), FETCHED_AT);

        assertThat(counts).isEqualTo(expectedCounts);
        verify(sourceLock).acquire(2L);

        ArgumentCaptor<DedupInput> inputCaptor = ArgumentCaptor.forClass(DedupInput.class);
        verify(resolver).resolve(inputCaptor.capture());
        DedupInput input = inputCaptor.getValue();
        assertThat(input.feedId()).isEqualTo(1L);
        assertThat(input.homepageKey()).isEqualTo("https://example.test/home");
        assertThat(input.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(input.lookup()).isSameAs(lookup);

        verify(telemetry).span(eq(IngestTelemetry.RESOLVE_SPAN),
                eq(Map.of(IngestTelemetry.SOURCE_ID_ATTRIBUTE, "2")), any());
        verify(applier).apply(2L, 1L, resolution, FETCHED_AT, NOW);
    }

    @Test
    void persistTakesTheLockBeforeReadingTheHomepageThenResolvesThenApplies() {
        stubHomepage("https://example.test/home");
        Resolution resolution = new Resolution(List.of(), Map.of());
        when(resolver.resolve(any())).thenReturn(resolution);
        when(applier.apply(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new PersistCounts(0, Map.of(), 0, 0, Map.of(), Map.of()));

        persister.persist(feed, List.of(), FETCHED_AT);

        InOrder inOrder = inOrder(sourceLock, jdbc, resolver, applier);
        inOrder.verify(sourceLock).acquire(2L);
        inOrder.verify(jdbc).sql(anyString());
        inOrder.verify(resolver).resolve(any());
        inOrder.verify(applier).apply(2L, 1L, resolution, FETCHED_AT, NOW);
    }

    @Test
    void persistHandlesNullHomepage() {
        stubHomepage(null);
        Resolution resolution = new Resolution(List.of(), Map.of(
                LinkFallback.GUID_REPLACED, 0,
                LinkFallback.GUARDED_HOMEPAGE, 0,
                LinkFallback.GUARDED_SHARED, 0));
        when(resolver.resolve(any())).thenReturn(resolution);
        when(applier.apply(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new PersistCounts(0, Map.of(), 0, 0, Map.of(), resolution.linkFallbacks()));

        persister.persist(feed, List.of(), FETCHED_AT);

        ArgumentCaptor<DedupInput> inputCaptor = ArgumentCaptor.forClass(DedupInput.class);
        verify(resolver).resolve(inputCaptor.capture());
        assertThat(inputCaptor.getValue().homepageKey()).isNull();
    }

    private static class MockMappedQuerySpec<T> implements JdbcClient.MappedQuerySpec<T> {
        private final T value;

        MockMappedQuerySpec(T value) {
            this.value = value;
        }

        @Override
        public Optional<T> optional() {
            return Optional.ofNullable(value);
        }

        @Override
        public T single() {
            return value;
        }

        @Override
        public List<T> list() {
            return value != null ? List.of(value) : List.of();
        }

        @Override
        public java.util.stream.Stream<T> stream() {
            return list().stream();
        }
    }
}
