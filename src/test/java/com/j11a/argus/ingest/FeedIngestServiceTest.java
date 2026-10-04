package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FailingThreshold;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.identity.FeedIdentityHooks;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.identity.FeedRedirectApplier.RedirectOutcome;
import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.source.Source;
import com.j11a.argus.testsupport.LogCapture;
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
    private final FeedRedirectApplier redirectApplier = mock(FeedRedirectApplier.class);
    private final FeedIdentityRegistry identityRegistry = mock(FeedIdentityRegistry.class);
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
                new IngestHealth(healthUpdater, healthGauges, new FailingThreshold(3)),
                new FeedIdentityHooks(redirectApplier, identityRegistry), clock);
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
                .thenReturn(new FeedLoader.Loaded.Parsed(parsed, FETCHED_AT, newValidators, null));
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
    void anUnexpectedLoaderExceptionRecordsAFailureAndPropagatesAsIngestFailed() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenThrow(new IllegalStateException("boom"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.refresh(1L))
                .isInstanceOfSatisfying(IngestFailedException.class, e -> {
                    assertThat(e.feedId()).isOne();
                    assertThat(e.reason()).isEqualTo(FailureReasons.UNEXPECTED_ERROR);
                    assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
                });

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
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED, null));
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IngestFailedException.class);

        verify(healthUpdater).recordFailure(1L, FailureReasons.PERSIST_FAILED, FETCHED_AT);
    }

    @Test
    void aPersistFailureOfAFeedDeletedMeanwhileIsNotLoggedAndKeepsTheOriginalException() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED, null));
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("foreign key violation"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(0);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IllegalStateException.class);

            assertThat(logs.at(Level.ERROR)).isEmpty();
            assertThat(logs.at(Level.WARN)).isEmpty();
        }
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
    void anUnexpectedLoaderExceptionIsOneErrorWithTheStackTraceAndTheExceptionTypeOnly() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenThrow(new IllegalStateException("boom"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(2);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IngestFailedException.class);

            assertThat(logs.at(Level.ERROR)).singleElement().satisfies(event -> {
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("reason", FailureReasons.UNEXPECTED_ERROR)
                        .containsEntry("errorType", "IllegalStateException")
                        .containsEntry("consecutiveFailures", 2)
                        .containsEntry("failingThreshold", 3)
                        .containsEntry("url", "https://example.test/rss.xml")
                        .doesNotContainKey("errorMessage");
                assertThat(event.getFormattedMessage()).doesNotContain("boom");
            });
            assertThat(logs.at(Level.WARN)).isEmpty();
        }
    }

    @Test
    void aPersistFailureNeverLogsTheExceptionTextBecauseItQuotesTheArticleRow() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED, null));
        IllegalStateException constraint = new IllegalStateException("wrapper",
                new IllegalStateException("Key (guid)=(GUID-SECRET) link https://news.test/a?t=LINK-SECRET exists"));
        when(persister.persist(any(), any(), any())).thenThrow(constraint);
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(1);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IngestFailedException.class);

            assertThat(logs.at(Level.ERROR)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", FailureReasons.PERSIST_FAILED)
                            .containsEntry("errorType", "IllegalStateException")
                            .doesNotContainKey("errorMessage"));
            logs.assertNothingLogged("GUID-SECRET");
            logs.assertNothingLogged("LINK-SECRET");
        }
    }

    @Test
    void aFailedPersistAtTheThresholdLogsTheFailureErrorAndTheCrossingErrorOnly() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED, null));
        when(persister.persist(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(3);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> service.refresh(1L)).isInstanceOf(IngestFailedException.class);

            assertThat(logs.at(Level.WARN)).isEmpty();
            assertThat(logs.at(Level.ERROR)).hasSize(2).satisfiesExactly(
                    failure -> {
                        assertThat(failure.getThrowableProxy()).isNotNull();
                        assertThat(LogCapture.keyValues(failure)).containsEntry("reason", FailureReasons.PERSIST_FAILED);
                    },
                    crossing -> {
                        assertThat(crossing.getThrowableProxy()).isNull();
                        assertThat(crossing.getFormattedMessage())
                                .contains("is now failing after 3 consecutive failures");
                    });
        }
    }

    @Test
    void aFetchFailureAtTheThresholdIsAWarnAndTheCrossingError() {
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Failed("io", null, new FetchError("ConnectException", "refused"),
                        null, null));
        when(healthUpdater.recordFailure(anyLong(), any(), any())).thenReturn(3);

        try (LogCapture logs = LogCapture.start()) {
            service.refresh(1L);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(event.getThrowableProxy()).isNull());
            assertThat(logs.at(Level.ERROR)).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("is now failing after 3 consecutive failures")
                            .contains("io ConnectException: refused"));
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
                assertThat(event.getFormattedMessage())
                        .contains("feed 1 (example.test)")
                        .contains(": io; 1 consecutive failures");
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

    private static final URI TARGET = URI.create("https://new.example.test/rss.xml");
    private static final String STORED_URL = "https://example.test/rss.xml";

    private void loadsParsedWithPermanentTarget(URI target) {
        when(loader.load(FEED_URI, STORED, "example.test")).thenReturn(
                new FeedLoader.Loaded.Parsed(oneEntryWithNothingOptional(), FETCHED_AT, STORED, target));
    }

    private static PersistCounts noCounts() {
        return new PersistCounts(1, Map.of(), 0, 0, Map.of(), Map.of());
    }

    @Test
    void aRedirectOntoAnotherFeedEndsTheIngestWithoutPersistingOrCountingAFailure() {
        loadsParsedWithPermanentTarget(TARGET);
        when(redirectApplier.apply(1L, "example.test", STORED_URL, TARGET))
                .thenReturn(new RedirectOutcome.Conflict(7L));

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
        assertThat(report.failureReason()).isEqualTo(FailureReasons.DUPLICATE_FEED);
        verifyNoInteractions(persister, identityRegistry);
        verify(healthUpdater, never()).recordFailure(anyLong(), any(), any());
        verify(healthUpdater, never()).recordSuccess(anyLong(), any(), any());
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void aNotModifiedAnswerReachedThroughAPermanentRedirectAppliesItAndRecordsNotModified() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenReturn(new FeedLoader.Loaded.NotModified(
                new FetchResult.NotModified(TARGET, TARGET, STORED)));
        when(redirectApplier.apply(1L, "example.test", STORED_URL, TARGET))
                .thenReturn(new RedirectOutcome.Applied(TARGET.toString()));

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.NOT_MODIFIED);
        verify(redirectApplier).apply(1L, "example.test", STORED_URL, TARGET);
        verify(healthUpdater).recordNotModified(1L, STORED, FETCHED_AT);
    }

    @Test
    void aNotModifiedAnswerThatRedirectsOntoAnotherFeedDoesNotRecordNotModified() {
        when(loader.load(FEED_URI, STORED, "example.test")).thenReturn(new FeedLoader.Loaded.NotModified(
                new FetchResult.NotModified(TARGET, TARGET, STORED)));
        when(redirectApplier.apply(1L, "example.test", STORED_URL, TARGET))
                .thenReturn(new RedirectOutcome.Conflict(7L));

        IngestReport report = service.refresh(1L);

        assertThat(report.failureReason()).isEqualTo(FailureReasons.DUPLICATE_FEED);
        verifyNoInteractions(healthUpdater);
    }

    @Test
    void anAppliedRedirectIsFollowedByTheNormalPersist() {
        loadsParsedWithPermanentTarget(TARGET);
        when(redirectApplier.apply(1L, "example.test", STORED_URL, TARGET))
                .thenReturn(new RedirectOutcome.Applied(TARGET.toString()));
        when(persister.persist(any(), any(), any())).thenReturn(noCounts());

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        verify(healthUpdater).recordSuccess(1L, STORED, FETCHED_AT);
    }

    @Test
    void aSkippedRedirectLeavesTheFeedAsItIsAndTheIngestGoesOn() {
        loadsParsedWithPermanentTarget(TARGET);
        when(redirectApplier.apply(1L, "example.test", STORED_URL, TARGET))
                .thenReturn(new RedirectOutcome.Skipped(7L));
        when(persister.persist(any(), any(), any())).thenReturn(noCounts());

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        verify(healthUpdater).recordSuccess(1L, STORED, FETCHED_AT);
        verify(healthUpdater, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    void aFetchWithoutAPermanentTargetNeverAsksTheApplier() {
        loadsParsedWithPermanentTarget(null);
        when(persister.persist(any(), any(), any())).thenReturn(noCounts());

        service.refresh(1L);

        verifyNoInteractions(redirectApplier);
    }

    @Test
    void theSelfLinkIsRecordedAfterASuccessfulPersist() {
        ParsedFeed parsed = new ParsedFeed("Example", "https://example.test/", "https://example.test/self.xml", null,
                List.of());
        when(loader.load(FEED_URI, STORED, "example.test"))
                .thenReturn(new FeedLoader.Loaded.Parsed(parsed, FETCHED_AT, STORED, null));
        when(persister.persist(any(), any(), any())).thenReturn(
                new PersistCounts(0, Map.of(), 0, 0, Map.of(), Map.of()));

        service.refresh(1L);

        verify(identityRegistry).recordSelfUrl(1L, "https://example.test/self.xml");
    }

    @Test
    void aFailureRecordingTheSelfLinkIsAWarnAndDoesNotFailTheIngest() {
        loadsParsedWithPermanentTarget(null);
        when(persister.persist(any(), any(), any())).thenReturn(noCounts());
        doThrow(new IllegalStateException("db down")).when(identityRegistry).recordSelfUrl(anyLong(), any());

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = service.refresh(1L);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("feedId", 1L));
        }
        verify(healthUpdater, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    void aPersistThatFindsTheFeedInAnotherSourceIsRetriedOnceWithTheFeedReRead() {
        Feed moved = mock(Feed.class);
        Source newSource = mock(Source.class);
        when(newSource.getId()).thenReturn(9L);
        when(newSource.getKey()).thenReturn("new.test");
        when(moved.getId()).thenReturn(1L);
        when(moved.getSource()).thenReturn(newSource);
        when(feedRepository.findWithSourceById(1L)).thenReturn(Optional.of(feed), Optional.of(moved));
        loadsParsedWithPermanentTarget(null);
        when(persister.persist(eq(feed), any(), any())).thenThrow(new FeedSourceChangedException(1L));
        when(persister.persist(eq(moved), any(), any())).thenReturn(noCounts());

        IngestReport report = service.refresh(1L);

        assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.COMPLETED);
        verify(persister).persist(eq(moved), any(), any());
        verify(healthUpdater).recordSuccess(1L, STORED, FETCHED_AT);
        verify(healthUpdater, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    void aSecondSourceMismatchIsAWarnedSourceChangedReportWithNoHealthWrites() {
        Feed moved = mock(Feed.class);
        Source newSource = mock(Source.class);
        when(newSource.getId()).thenReturn(9L);
        when(newSource.getKey()).thenReturn("new.test");
        when(moved.getId()).thenReturn(1L);
        when(moved.getSource()).thenReturn(newSource);
        when(feedRepository.findWithSourceById(1L)).thenReturn(Optional.of(feed), Optional.of(moved));
        loadsParsedWithPermanentTarget(null);
        when(persister.persist(any(), any(), any())).thenThrow(new FeedSourceChangedException(1L));

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = service.refresh(1L);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
            assertThat(report.failureReason()).isEqualTo(FailureReasons.SOURCE_CHANGED);
            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", FailureReasons.SOURCE_CHANGED)
                            .containsEntry("sourceKey", "new.test"));
        }
        verify(healthUpdater, never()).recordFailure(anyLong(), any(), any());
        verify(healthUpdater, never()).recordSuccess(anyLong(), any(), any());
        verify(persister, org.mockito.Mockito.times(2)).persist(any(), any(), any());
        verify(healthGauges).refreshAfterCommit();
    }
}
