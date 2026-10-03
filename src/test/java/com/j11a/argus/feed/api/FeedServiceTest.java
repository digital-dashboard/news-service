package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;

class FeedServiceTest {

    private static final String URL = "https://example.test/rss.xml";
    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private final FeedRepository feeds = mock(FeedRepository.class);
    private final FeedInserter inserter = mock(FeedInserter.class);
    private final SourceService sources = mock(SourceService.class);
    private final FeedLoader loader = mock(FeedLoader.class);
    private final FeedIngestService ingest = mock(FeedIngestService.class);
    private final FeedHealthUpdater healthUpdater = mock(FeedHealthUpdater.class);
    private final FeedHealthGauges healthGauges = mock(FeedHealthGauges.class);
    private final PollProperties properties = new PollProperties("0 */15 * * * *", 8, 3);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final IngestTelemetry.CreateFetchTimer timer = mock(IngestTelemetry.CreateFetchTimer.class);
    private final Clock clock = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);
    private final FeedService service = new FeedService(feeds, inserter, sources, loader, ingest,
            healthUpdater, healthGauges, properties, jdbc, clock);

    @BeforeEach
    void stubSource() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(3L);
        when(source.getKey()).thenReturn("example.test");
        when(sources.findOrCreate(anyString(), any())).thenReturn(source);
        when(inserter.findIdByUrl(URL)).thenReturn(Optional.empty());
        when(loader.startCreateFetch()).thenReturn(timer);
    }

    private void loads(String title, String language) {
        ParsedFeed parsed = new ParsedFeed(title, "https://example.test/", null, language, List.of());
        when(loader.loadForCreate(any(URI.class), eq(timer))).thenReturn(
                new FeedLoader.CreateLoaded.Created(parsed, FETCHED_AT, FetchValidators.EMPTY, 100));
    }

    private static Feed storedFeed() {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(9L);
        when(feed.getUrl()).thenReturn(URL);
        Source source = mock(Source.class);
        when(feed.getSource()).thenReturn(source);
        return feed;
    }

    private static CreateFeedRequest request() {
        return new CreateFeedRequest(URL, null, Topic.TECH);
    }

    @Test
    void aRowInsertedBetweenTheCheckAndTheInsertGives409WithTheWinnersId() {
        loads("Example", null);
        when(inserter.insert(any())).thenReturn(Optional.empty());
        when(inserter.findIdByUrl(URL)).thenReturn(Optional.empty(), Optional.of(77L));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_URL_CONFLICT);
                    assertThat(e.properties()).containsEntry("existingFeedId", 77L);
                });
        verifyNoInteractions(ingest);
    }

    @Test
    void anExistingUrlIsRejectedBeforeAnyDownload() {
        when(inserter.findIdByUrl(URL)).thenReturn(Optional.of(5L));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.properties()).containsEntry("existingFeedId", 5L));
        verifyNoInteractions(loader);
    }

    @Test
    void theDefaultedNameIsCutToTheColumnWidthAndAnOverlongLanguageIsDropped() {
        loads("T".repeat(300), "x".repeat(20));
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(request());

        ArgumentCaptor<NewFeed> inserted = ArgumentCaptor.forClass(NewFeed.class);
        verify(inserter).insert(inserted.capture());
        assertThat(inserted.getValue().name()).hasSize(FeedService.MAX_NAME_LENGTH);
        assertThat(inserted.getValue().language()).isNull();
        verify(healthUpdater).recordSuccess(9L, FetchValidators.EMPTY, FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
        verify(timer).completed("example.test", 100);
    }

    @Test
    void aLanguageAtTheLimitIsKept() {
        loads("Example", "x".repeat(FeedService.MAX_LANGUAGE_LENGTH));
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(request());

        ArgumentCaptor<NewFeed> inserted = ArgumentCaptor.forClass(NewFeed.class);
        verify(inserter).insert(inserted.capture());
        assertThat(inserted.getValue().language()).hasSize(FeedService.MAX_LANGUAGE_LENGTH);
    }

    private NewFeed insertedFeedFor(CreateFeedRequest request) {
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(request);

        ArgumentCaptor<NewFeed> inserted = ArgumentCaptor.forClass(NewFeed.class);
        verify(inserter).insert(inserted.capture());
        return inserted.getValue();
    }

    @Test
    void aUrlThatIsNotAbsoluteHttpIsABadRequestBeforeAnyLookup() {
        CreateFeedRequest request = new CreateFeedRequest("ftp://example.test/rss.xml", null, Topic.TECH);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(inserter, loader);
    }

    @Test
    void aFeedThatCannotBeReadIsRejectedWithTheLoaderReason() {
        when(loader.loadForCreate(any(URI.class), eq(timer))).thenReturn(new FeedLoader.CreateLoaded.Failed("not_a_feed"));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_INVALID);
                    assertThat(e.properties()).containsEntry("reason", "not_a_feed");
                });
    }

    @Test
    void aFailedFirstIngestStillReturnsTheCreatedFeed() {
        loads("Example", null);
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));
        doThrow(new IllegalStateException("database down")).when(ingest).ingestParsed(any(), any(), any());

        FeedResponse response = service.create(request());

        assertThat(response.id()).isEqualTo(9L);
        verify(healthUpdater).recordFailure(9L, FailureReasons.FIRST_INGEST_FAILED, FETCHED_AT);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void aRequestedNameIsStrippedAndWinsOverTheFeedTitle() {
        loads("Parsed title", null);

        NewFeed inserted = insertedFeedFor(new CreateFeedRequest(URL, "  Custom name  ", Topic.TECH));

        assertThat(inserted.name()).isEqualTo("Custom name");
    }

    @Test
    void aBlankRequestedNameFallsBackToTheFeedTitle() {
        loads("Parsed title", null);

        NewFeed inserted = insertedFeedFor(new CreateFeedRequest(URL, "   ", Topic.TECH));

        assertThat(inserted.name()).isEqualTo("Parsed title");
    }

    @Test
    void withNeitherANameNorATitleTheSourceKeyIsTheName() {
        loads("  ", null);

        NewFeed inserted = insertedFeedFor(request());

        assertThat(inserted.name()).isEqualTo("example.test");
    }

    @Test
    void getReturnsTheStoredFeed() {
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        assertThat(service.get(9L).id()).isEqualTo(9L);
    }

    @Test
    void getOfAnUnknownIdIsFeedNotFound() {
        when(feeds.findWithSourceById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Feed 404 does not exist.");
                });
    }

    @Test
    void listReturnsPagedFeedResponses() {
        Feed stored = storedFeed();
        when(feeds.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(stored)));

        Page<FeedResponse> page = service.list(0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().id()).isEqualTo(9L);
    }

    private JdbcClient.StatementSpec statementUpdating(int rows) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(spec.update()).thenReturn(rows);
        when(jdbc.sql(anyString())).thenReturn(spec);
        return spec;
    }

    @Test
    void patchUpdatesEnabledStateWithOneStatementAndRefreshesGaugesAfterCommit() {
        JdbcClient.StatementSpec update = statementUpdating(1);
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        FeedResponse response = service.patch(9L, new PatchFeedRequest(false));

        verify(update).param("enabled", false);
        verify(update).param("id", 9L);
        verify(update).update();
        verify(feeds, never()).save(any());
        verify(healthGauges).refreshAfterCommit();
        assertThat(response.id()).isEqualTo(9L);
    }

    @Test
    void patchOfUnknownFeedThrowsNotFoundWithoutRefreshingGauges() {
        statementUpdating(0);

        assertThatThrownBy(() -> service.patch(404L, new PatchFeedRequest(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        verifyNoInteractions(healthGauges);
    }

    @Test
    void deleteRemovesOwnedArticlesThenTheFeedAndRefreshesGaugesAfterCommit() {
        JdbcClient.StatementSpec statement = statementUpdating(1);

        service.delete(9L);

        verify(jdbc).sql(contains("DELETE FROM article a USING article_feed mine"));
        verify(jdbc).sql("DELETE FROM feed WHERE id = :id");
        verify(statement).param("feedId", 9L);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void deleteOfUnknownFeedThrowsNotFoundWithoutRefreshingGauges() {
        statementUpdating(0);

        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        verifyNoInteractions(healthGauges);
    }
}
