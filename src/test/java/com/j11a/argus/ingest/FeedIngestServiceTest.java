package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedIngestServiceTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ArticlePersister persister = mock(ArticlePersister.class);
    private final Feed feed = mock(Feed.class);
    private FeedIngestService service;

    @BeforeEach
    void wire() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(2L);
        when(source.getKey()).thenReturn("example.test");
        when(feed.getId()).thenReturn(1L);
        when(feed.getSource()).thenReturn(source);
        IngestTelemetry telemetry = new IngestTelemetry(ObservationRegistry.create(), registry, Tracer.NOOP);
        service = new FeedIngestService(mock(FeedRepository.class), mock(FeedLoader.class), persister, telemetry);
    }

    private static ParsedFeed oneEntryWithNothingOptional() {
        ParsedEntry entry = new ParsedEntry(null, "https://example.test/a", "A", "", null, null, List.of(), null, null);
        return new ParsedFeed("Example", "https://example.test/", null, null, List.of(entry));
    }

    @Test
    void dataQualityAndDecisionCountersAreRecordedOnlyAfterThePersistCommits() {
        when(persister.persist(any(), any(), any())).thenReturn(new PersistCounts(1, 0, java.util.Map.of()));

        service.ingestParsed(feed, oneEntryWithNothingOptional(), FETCHED_AT);

        assertThat(registry.find(MetricNames.PARSE_MISSING).counters()).isNotEmpty();
        assertThat(registry.find(MetricNames.INGEST_ENTRIES).counters()).isNotEmpty();
    }

    @Test
    void whenPersistFailsNeitherCounterIsRecorded() {
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.ingestParsed(feed, oneEntryWithNothingOptional(), FETCHED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(MetricNames.PARSE_MISSING).counters()).isEmpty();
        assertThat(registry.find(MetricNames.INGEST_ENTRIES).counters()).isEmpty();
    }
}
