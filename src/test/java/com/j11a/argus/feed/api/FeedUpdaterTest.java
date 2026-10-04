package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedIdentityTelemetry;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.source.FeedSourceChangedException;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.PatchRequests;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class FeedUpdaterTest {

    private static final String STORED_URL = "https://example.test/rss.xml";

    private final FeedRepository feeds = mock(FeedRepository.class);
    private final SourceService sources = mock(SourceService.class);
    private final SourceMerger merger = mock(SourceMerger.class);
    private final FeedLoader loader = mock(FeedLoader.class);
    private final FeedIdentityRegistry registry = mock(FeedIdentityRegistry.class);
    private final FeedIdentityTelemetry telemetry = mock(FeedIdentityTelemetry.class);
    private final FeedHealthGauges healthGauges = mock(FeedHealthGauges.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final FeedUpdater updater = new FeedUpdater(feeds, sources, merger,
            new FeedUrlChecks(registry, telemetry, new FeedProbe(loader)), healthGauges, jdbc,
            Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void storedFeed() {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(9L);
        when(feed.getUrl()).thenReturn(STORED_URL);
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(feed));
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void anEmptyRequestIsAValidationFailureBeforeAnyLookup() {
        PatchFeedRequest empty = new PatchFeedRequest(null, null, null, null, null);

        assertThatThrownBy(() -> updater.patch(9L, empty)).satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(feeds, jdbc);
    }

    @Test
    void aBlankNameIsAValidationFailureBeforeAnyWrite() {
        PatchFeedRequest blank = new PatchFeedRequest(null, "   ", null, null, null);

        assertThatThrownBy(() -> updater.patch(9L, blank)).satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(jdbc);
    }

    @Test
    void anUnknownFeedIsNotFound() {
        when(feeds.findWithSourceById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updater.patch(404L, PatchRequests.enabled(false)))
                .satisfies(e -> assertCode(e, ErrorCode.FEED_NOT_FOUND));
    }

    @Test
    void anUnknownTargetSourceIsRefusedBeforeAnyWriteEvenWhenOtherFieldsAreValid() {
        when(sources.findById(77L)).thenReturn(Optional.empty());
        PatchFeedRequest request = new PatchFeedRequest(false, "New", Topic.TECH, 77L, null);

        assertThatThrownBy(() -> updater.patch(9L, request)).satisfies(e -> assertCode(e, ErrorCode.SOURCE_NOT_FOUND));
        verifyNoInteractions(jdbc, merger);
    }

    @Test
    void aUrlThatCleansToTheStoredOneIsNeitherCheckedNorDownloaded() {
        updater.patch(9L, new PatchFeedRequest(null, null, null, null, "HTTPS://Example.test/rss.xml#top"));

        verifyNoInteractions(loader, registry, jdbc);
    }

    @Test
    void aUrlThatNamesAnotherFeedIsAConflictBeforeTheDownloadAndBeforeAnyWrite() {
        when(registry.findConflict(any(), eq(9L)))
                .thenReturn(Optional.of(new FeedIdentityRegistry.Conflict(IdentityKind.ENTERED, 4L, false)));

        assertThatThrownBy(() -> updater.patch(9L, new PatchFeedRequest(null, null, null, null,
                "https://other.test/feed.xml")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_URL_CONFLICT);
                    assertThat(e.properties()).containsEntry("existingFeedId", 4L).containsEntry("kind", "entered");
                });
        verifyNoInteractions(loader, jdbc);
        verify(telemetry).conflict(IdentityKind.ENTERED);
    }

    @Test
    void aMoveThatFindsTheFeedInYetAnotherSourceIsRetriedOnce() {
        when(sources.findById(3L)).thenReturn(Optional.of(mock(Source.class)));
        when(merger.moveFeed(9L, 3L)).thenThrow(new FeedSourceChangedException(9L)).thenReturn(null);

        updater.patch(9L, new PatchFeedRequest(null, null, null, 3L, null));

        verify(merger, times(2)).moveFeed(9L, 3L);
    }

    @Test
    void aMoveThatFindsTheFeedElsewhereTwiceIsAConflict() {
        when(sources.findById(3L)).thenReturn(Optional.of(mock(Source.class)));
        doThrow(new FeedSourceChangedException(9L)).when(merger).moveFeed(9L, 3L);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> updater.patch(9L, new PatchFeedRequest(null, null, null, 3L, null)))
                    .satisfies(e -> assertCode(e, ErrorCode.CONFLICT))
                    .hasCauseInstanceOf(FeedSourceChangedException.class);

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("feedId", 9L)
                            .containsEntry("reason", "source_changed"));
        }
        verify(merger, times(2)).moveFeed(9L, 3L);
    }

    @Test
    void aRequestWithoutFieldsToUpdateIssuesNoFieldUpdate() {
        when(sources.findById(3L)).thenReturn(Optional.of(mock(Source.class)));

        updater.patch(9L, new PatchFeedRequest(null, null, null, 3L, null));

        verify(jdbc, never()).sql(any());
        verify(registry, never()).findConflict(any(), isNull());
    }
}
