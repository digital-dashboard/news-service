package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private final com.j11a.argus.config.PollProperties properties =
            new com.j11a.argus.config.PollProperties("0 */15 * * * *", 8, 3);
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc =
            mock(org.springframework.jdbc.core.simple.JdbcClient.class);
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
    }

    private void loads(String title, String language) {
        ParsedFeed parsed = new ParsedFeed(title, "https://example.test/", null, language, List.of());
        when(loader.loadForCreate(any(URI.class))).thenReturn(
                new FeedLoader.Loaded.CreateParsed(parsed, FETCHED_AT, FetchValidators.EMPTY, 100, mock(Timer.Sample.class)));
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
        verify(healthGauges).refresh();
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
        when(loader.loadForCreate(any(URI.class))).thenReturn(new FeedLoader.Loaded.Failed("not_a_feed"));

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
        verify(healthUpdater).recordFailure(9L, "first_ingest_failed", FETCHED_AT);
        verify(healthGauges).refresh();
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
        when(feeds.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(stored)));

        org.springframework.data.domain.Page<FeedResponse> page = service.list(0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().id()).isEqualTo(9L);
    }

    @Test
    void patchUpdatesEnabledStateAndRefreshesGauges() {
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));
        when(feeds.save(stored)).thenReturn(stored);

        FeedResponse response = service.patch(9L, new PatchFeedRequest(false));

        verify(stored).setEnabled(false);
        verify(feeds).save(stored);
        verify(healthGauges).refresh();
        assertThat(response.id()).isEqualTo(9L);
    }

    @Test
    void patchOfUnknownFeedThrowsNotFound() {
        when(feeds.findWithSourceById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patch(404L, new PatchFeedRequest(false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
    }

    @Test
    void deleteRemovesFeedOrphanArticlesAndRefreshesGauges() {
        Feed stored = storedFeed();
        when(feeds.findById(9L)).thenReturn(Optional.of(stored));
        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec selectSpec =
                mock(org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec<Long> querySpec =
                mock(org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec.class);
        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec deleteSpec =
                mock(org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class);

        when(jdbc.sql(org.mockito.ArgumentMatchers.contains("SELECT article_id"))).thenReturn(selectSpec);
        when(selectSpec.param("feedId", 9L)).thenReturn(selectSpec);
        when(selectSpec.query(Long.class)).thenReturn(querySpec);
        when(querySpec.list()).thenReturn(List.of(101L));

        when(jdbc.sql(org.mockito.ArgumentMatchers.contains("DELETE FROM article"))).thenReturn(deleteSpec);
        when(deleteSpec.param(org.mockito.ArgumentMatchers.eq("ids"), any())).thenReturn(deleteSpec);
        when(deleteSpec.update()).thenReturn(1);

        service.delete(9L);

        verify(feeds).delete(stored);
        verify(feeds).flush();
        verify(deleteSpec).update();
        verify(healthGauges).refresh();
    }

    @Test
    void deleteOfUnknownFeedThrowsNotFound() {
        when(feeds.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        verifyNoInteractions(healthGauges);
    }
}
