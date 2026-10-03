package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import ch.qos.logback.classic.Level;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.source.Source;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedIngestServiceTest {

    private static final URI FEED_URI = URI.create("https://example.test/rss.xml");
    private static final FetchValidators STORED = new FetchValidators("\"etag1\"", "Wed, 21 Oct 2026 07:28:00 GMT");

    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ArticlePersister persister = mock(ArticlePersister.class);
    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final FeedLoader loader = mock(FeedLoader.class);
    private final FeedHealthUpdater healthUpdater = mock(FeedHealthUpdater.class);
    private final FeedHealthGauges healthGauges = mock(FeedHealthGauges.class);
    private final Clock clock = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);
    private final Feed feed = mock(Feed.class);
    private FeedIngestService service;

    @BeforeEach
    void wire() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(2L);
        when(source.getKey()).thenReturn("example.test");
        when(feed.getId()).thenReturn(1L);
        when(feed.getUrl()).thenReturn("https://example.test/rss.xml");
        when(feed.getSource()).thenReturn(source);
        when(feed.getEtag()).thenReturn("\"etag1\"");
        when(feed.getLastModified()).thenReturn("Wed, 21 Oct 2026 07:28:00 GMT");
        when(feedRepository.findWithSourceById(1L)).thenReturn(Optional.of(feed));

        IngestTelemetry telemetry = new IngestTelemetry(ObservationRegistry.create(), registry, Tracer.NOOP);
        service = new FeedIngestService(feedRepository, loader, persister, telemetry,
                healthUpdater, healthGauges, new PollProperties("0 */15 * * * *", 8, 3), clock);
    }

    private static ParsedFeed oneEntryWithNothingOptional() {
        ParsedEntry entry = new ParsedEntry(null, "https://example.test/a", "A", "", null, null, List.of(), null, null);
        return new ParsedFeed("Example", "https://example.test/", null, null, List.of(entry));
    }

    @Test
    void dataQualityAndDecisionCountersAreRecordedOnlyAfterThePersistCommits() {
        when(persister.persist(any(), any(), any())).thenReturn(
                new PersistCounts(1, Map.of(), 0, 0, Map.of(), Map.of()));

        service.ingestParsed(feed, oneEntryWithNothingOptional(), FETCHED_AT);

        assertThat(registry.find(MetricNames.PARSE_MISSING).counters()).isNotEmpty();
        assertThat(registry.find(MetricNames.INGEST_ENTRIES).counters()).isNotEmpty();
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void whenPersistFailsNeitherCounterIsRecorded() {
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        ParsedFeed parsedFeed = oneEntryWithNothingOptional();

        assertThatThrownBy(() -> service.ingestParsed(feed, parsedFeed, FETCHED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(MetricNames.PARSE_MISSING).counters()).isEmpty();
        assertThat(registry.find(MetricNames.INGEST_ENTRIES).counters()).isEmpty();
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void refreshOnNotModifiedRecordsHealthAndLeavesArticlesUntouched() {
        FetchValidators validators = new FetchValidators("\"etag1\"", "Wed, 21 Oct 2026 07:28:00 GMT");
        when(loader.load(URI.create("https://example.test/rss.xml"), validators, "example.test"))
                .thenReturn(new FeedLoader.Loaded.NotModified(new FetchResult.NotModified(URI.create("https://example.test/rss.xml"), null, validators)));

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.NOT_MODIFIED);
        verify(healthUpdater).recordNotModified(1L, validators, FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void refreshOnFailureRecordsHealthFailure() {
        FetchValidators validators = new FetchValidators("\"etag1\"", "Wed, 21 Oct 2026 07:28:00 GMT");
        when(loader.load(URI.create("https://example.test/rss.xml"), validators, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Failed("http_status", 503, null, null, null));

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.failureReason()).isEqualTo("http_status");
        verify(healthUpdater).recordFailure(1L, "http_status 503", FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void refreshOnSuccessPersistsAndRecordsSuccess() {
        FetchValidators validators = new FetchValidators("\"etag1\"", "Wed, 21 Oct 2026 07:28:00 GMT");
        FetchValidators newValidators = new FetchValidators("\"etag2\"", "Thu, 22 Oct 2026 07:28:00 GMT");
        ParsedFeed parsed = oneEntryWithNothingOptional();
        when(loader.load(URI.create("https://example.test/rss.xml"), validators, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(parsed, FETCHED_AT, newValidators));
        when(persister.persist(any(), any(), any())).thenReturn(
                new PersistCounts(1, Map.of(), 0, 0, Map.of(), Map.of()));

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        verify(healthUpdater).recordSuccess(1L, newValidators, FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void anUnexpectedLoaderExceptionRecordsAFailureAndPropagates() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

        verify(healthUpdater).recordFailure(1L, FailureReasons.UNEXPECTED_ERROR, FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void anInterruptedLoadWritesNoHealthAndDoesNotRefreshGauges() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new FeedLoader.Loaded.Failed("io", null, null, null, null);
        });

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.failureReason()).isEqualTo(FailureReasons.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(healthUpdater, healthGauges);
    }

    @Test
    void anExceptionOnAnInterruptedThreadDoesNotCountAgainstTheFeed() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted mid-load");
        });

        assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

        verify(healthUpdater, never()).recordFailure(any(Long.class), any(), any());
    }

    @Test
    void aFailedPersistRecordsPersistFailedAndPropagates() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED));
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

        verify(healthUpdater).recordFailure(1L, FailureReasons.PERSIST_FAILED, FETCHED_AT);
    }

    @Test
    void refreshOfAMissingFeedIsFeedNotFound() {
        when(feedRepository.findWithSourceById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(404L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        verifyNoInteractions(healthUpdater);
    }

    @Test
    void anUnexpectedLoaderExceptionIsAWarnNamingTheExceptionTypeAndTheFailureCount() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenThrow(new IllegalStateException("boom"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(2);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", FailureReasons.UNEXPECTED_ERROR)
                            .containsEntry("errorType", "IllegalStateException")
                            .containsEntry("errorMessage", "boom")
                            .containsEntry("consecutiveFailures", 2)
                            .containsEntry("url", "https://example.test/rss.xml"));
            assertThat(logs.at(Level.ERROR)).isEmpty();
        }
    }

    @Test
    void aFailedPersistIsAWarnWithThePersistFailedReasonAndTheCrossingErrorAtTheThreshold() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED));
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(3);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("reason", FailureReasons.PERSIST_FAILED));
            assertThat(logs.at(Level.ERROR)).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).contains("is now failing after 3 consecutive failures"));
        }
    }

    @Test
    void aFailureWithoutAnErrorStillLogsTheReasonAlone() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Failed("io", null, null, null, null));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(1);

        try (LogCapture logs = LogCapture.start()) {
            service.refresh(1L);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Ingest failed for feed 1 (example.test): io; 1 consecutive failures");
                assertThat(LogCapture.keyValues(event)).doesNotContainKeys("errorType", "errorMessage");
            });
        }
    }

    @Test
    void aFailureWithAnErrorTypeButNoMessageOmitsTheMessage() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenReturn(new FeedLoader.Loaded.Failed("io", null,
                new FetchError("ConnectException", null), null, null));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(1);

        try (LogCapture logs = LogCapture.start()) {
            service.refresh(1L);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).endsWith(": io ConnectException; 1 consecutive failures"));
        }
    }
}
