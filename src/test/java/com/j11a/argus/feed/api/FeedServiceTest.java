package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.FeedIdentityTelemetry;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.PatchRequests;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class FeedServiceTest {

    private static final String URL = "https://example.test/rss.xml";
    private static final Instant FETCHED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private final FeedRepository feeds = mock(FeedRepository.class);
    private final FeedInserter inserter = mock(FeedInserter.class);
    private final SourceService sources = mock(SourceService.class);
    private final FeedLoader loader = mock(FeedLoader.class);
    private final FeedIdentityRegistry registry = mock(FeedIdentityRegistry.class);
    private final FeedIdentityTelemetry identityTelemetry = mock(FeedIdentityTelemetry.class);
    private final FeedUpdater updater = mock(FeedUpdater.class);
    private final FeedRemover remover = mock(FeedRemover.class);
    private final FeedIngestService ingest = mock(FeedIngestService.class);
    private final FeedHealthUpdater healthUpdater = mock(FeedHealthUpdater.class);
    private final FeedHealthGauges healthGauges = mock(FeedHealthGauges.class);
    private final PollProperties properties = new PollProperties("0 */15 * * * *", 8, 3);
    private final IngestTelemetry.CreateFetchTimer timer = mock(IngestTelemetry.CreateFetchTimer.class);
    private final Clock clock = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);
    private final SourceLock sourceLock = mock(SourceLock.class);
    private final FeedCreator creator = new FeedCreator(feeds, inserter, sources, sourceLock,
            new FeedUrlChecks(registry, identityTelemetry, new FeedProbe(loader)));
    private final FeedService service = new FeedService(feeds, creator,
            new FirstIngest(ingest, healthUpdater, healthGauges, clock), updater, remover, properties);

    @BeforeEach
    void stubSource() {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(3L);
        when(source.getKey()).thenReturn("example.test");
        when(sources.resolveAutomatic(any(), any(), any(URI.class))).thenReturn(source);
        when(sources.exists(3L)).thenReturn(true);
        when(loader.startCreateFetch()).thenReturn(timer);
        when(registry.withIdentityLock(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
    }

    private void loads(String title, String language) {
        loads(new ParsedFeed(title, "https://example.test/", null, language, List.of()), URI.create(URL));
    }

    private void loads(ParsedFeed parsed, URI finalUrl) {
        when(loader.loadForCreate(any(URI.class), eq(timer))).thenReturn(
                new FeedLoader.CreateLoaded.Created(parsed, FETCHED_AT, FetchValidators.EMPTY, 100, finalUrl));
    }

    private static Feed storedFeed() {
        Feed feed = mock(Feed.class);
        when(feed.getId()).thenReturn(9L);
        when(feed.getUrl()).thenReturn(URL);
        when(feed.getName()).thenReturn("Stored feed");
        Source source = mock(Source.class);
        when(feed.getSource()).thenReturn(source);
        return feed;
    }

    private static CreateFeedRequest request() {
        return new CreateFeedRequest(URL, null, Topic.TECH, null);
    }

    @Test
    void aRowInsertedBetweenTheCheckAndTheInsertGives409WithTheWinnersId() {
        loads("Example", null);
        when(inserter.insert(any())).thenReturn(Optional.empty());
        when(registry.findConflict(any(), isNull())).thenReturn(Optional.empty(), Optional.empty(),
                Optional.of(new FeedIdentityRegistry.Conflict(IdentityKind.ENTERED, 77L, false)));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FEED_URL_CONFLICT);
                    assertThat(e.properties()).containsEntry("existingFeedId", 77L).containsEntry("kind", "entered");
                });
        verifyNoInteractions(ingest);
    }

    @Test
    void anEnteredUrlThatNamesAnExistingFeedIsRejectedBeforeAnyDownload() {
        when(registry.findConflict(any(), isNull()))
                .thenReturn(Optional.of(new FeedIdentityRegistry.Conflict(IdentityKind.ENTERED, 5L, false)));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.properties()).containsEntry("existingFeedId", 5L).containsEntry("kind", "entered");
                });
        verifyNoInteractions(loader);
        verify(identityTelemetry).conflict(IdentityKind.ENTERED);
    }

    @Test
    void theDownloadIsCheckedAsEnteredThenRedirectThenSelfLinkAndAConflictCountsItsKind() {
        URI redirected = URI.create("https://new.example.test/rss.xml");
        loads(new ParsedFeed("T", "https://example.test/", "https://example.test/self.xml", null, List.of()),
                redirected);
        when(registry.findConflict(any(), isNull())).thenReturn(Optional.empty(),
                Optional.of(new FeedIdentityRegistry.Conflict(IdentityKind.REDIRECT, 8L, false)));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.properties()).containsEntry("existingFeedId", 8L).containsEntry("kind", "redirect");
                });

        ArgumentCaptor<List<FeedIdentityRegistry.Candidate>> candidates = ArgumentCaptor.captor();
        verify(registry, org.mockito.Mockito.times(2)).findConflict(candidates.capture(), isNull());
        assertThat(candidates.getAllValues().getLast()).containsExactly(
                new FeedIdentityRegistry.Candidate(IdentityKind.ENTERED, URL),
                new FeedIdentityRegistry.Candidate(IdentityKind.REDIRECT, "https://new.example.test/rss.xml"),
                new FeedIdentityRegistry.Candidate(IdentityKind.SELF_LINK, "https://example.test/self.xml"));
        verify(identityTelemetry).conflict(IdentityKind.REDIRECT);
        verify(inserter, never()).insert(any());
        verify(sources, never()).resolveAutomatic(any(), any(), any());
    }

    @Test
    void aFinalUrlAndSelfLinkEqualToTheEnteredUrlAreNotCheckedTwice() {
        loads(new ParsedFeed("T", "https://example.test/", URL, null, List.of()), URI.create(URL));
        insertedFeedFor(request());

        ArgumentCaptor<List<FeedIdentityRegistry.Candidate>> candidates = ArgumentCaptor.captor();
        verify(registry, org.mockito.Mockito.times(2)).findConflict(candidates.capture(), isNull());
        assertThat(candidates.getAllValues().getLast())
                .containsExactly(new FeedIdentityRegistry.Candidate(IdentityKind.ENTERED, URL));
    }

    @Test
    void theCleanedSelfLinkIsStoredAndTheSourceIsResolvedFromTheSiteAndSelfLinks() {
        loads(new ParsedFeed("T", "https://example.test/", "HTTPS://Example.test/self.xml#frag", null, List.of()),
                URI.create(URL));

        NewFeed inserted = insertedFeedFor(request());

        assertThat(inserted.selfUrl()).isEqualTo("https://example.test/self.xml");
        verify(sources).resolveAutomatic("https://example.test/", "HTTPS://Example.test/self.xml#frag",
                URI.create(URL));
    }

    @Test
    void explicitSourceNotFoundGives404BeforeAnyDownload() {
        when(sources.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateFeedRequest(URL, null, Topic.TECH, 99L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                });
        verifyNoInteractions(loader);
        verify(inserter, never()).insert(any());
    }

    @Test
    void explicitSourceAttachesFeedAndTagsTimerWithSourceKey() {
        loads("Example", null);
        Source explicit = mock(Source.class);
        when(explicit.getId()).thenReturn(42L);
        when(explicit.getKey()).thenReturn("explicit.test");
        when(sources.findById(42L)).thenReturn(Optional.of(explicit));
        when(sources.exists(42L)).thenReturn(true);
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(new CreateFeedRequest(URL, null, Topic.TECH, 42L));

        ArgumentCaptor<NewFeed> inserted = ArgumentCaptor.forClass(NewFeed.class);
        verify(inserter).insert(inserted.capture());
        assertThat(inserted.getValue().sourceId()).isEqualTo(42L);
        verify(timer).completed("explicit.test", 100);
        verify(sources, never()).resolveAutomatic(any(), any(), any());
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
        CreateFeedRequest request = new CreateFeedRequest("ftp://example.test/rss.xml", null, Topic.TECH, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(inserter, loader, registry);
    }

    @Test
    void aFeedThatCannotBeReadIsRejectedWithTheLoaderReason() {
        when(loader.loadForCreate(any(URI.class), eq(timer))).thenReturn(new FeedLoader.CreateLoaded.Failed("not_a_feed", null, null, null));

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

        NewFeed inserted = insertedFeedFor(new CreateFeedRequest(URL, "  Custom name  ", Topic.TECH, null));

        assertThat(inserted.name()).isEqualTo("Custom name");
    }

    @Test
    void aBlankRequestedNameFallsBackToTheFeedTitle() {
        loads("Parsed title", null);

        NewFeed inserted = insertedFeedFor(new CreateFeedRequest(URL, "   ", Topic.TECH, null));

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

    @Test
    void patchDelegatesToTheUpdaterAndReturnsTheStoredFeed() {
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));
        PatchFeedRequest request = PatchRequests.enabled(false);

        FeedResponse response = service.patch(9L, request);

        verify(updater).patch(9L, request);
        assertThat(response.id()).isEqualTo(9L);
    }

    @Test
    void deleteDelegatesToTheRemover() {
        service.delete(9L);

        verify(remover).delete(9L);
    }

    private static Source sourceWithId(long id) {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(id);
        when(source.getKey()).thenReturn("source-" + id + ".test");
        return source;
    }

    @Test
    void anExplicitSourceThatIsGoneOnceItIsLockedIs404AndNothingIsInserted() {
        loads("Example", null);
        Source explicit = sourceWithId(42L);
        when(sources.findById(42L)).thenReturn(Optional.of(explicit));
        when(sources.exists(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateFeedRequest(URL, null, Topic.TECH, 42L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Source 42 does not exist.");
                });
        verify(sourceLock).acquire(42L);
        verify(inserter, never()).insert(any());
        verifyNoInteractions(ingest);
    }

    @Test
    void theSourceIsLockedBeforeTheInsert() {
        loads("Example", null);
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(request());

        InOrder order = inOrder(sourceLock, inserter);
        order.verify(sourceLock).acquire(3L);
        order.verify(inserter).insert(any());
    }

    @Test
    void anAutomaticallyResolvedSourceThatIsGoneOnceItIsLockedIsResolvedAgainOnceAndThatOneIsLocked() {
        loads("Example", null);
        Source merged = sourceWithId(3L);
        Source fresh = sourceWithId(4L);
        when(sources.resolveAutomatic(any(), any(), any(URI.class))).thenReturn(merged, fresh);
        when(sources.exists(3L)).thenReturn(false);
        when(sources.exists(4L)).thenReturn(true);
        when(inserter.insert(any())).thenReturn(Optional.of(9L));
        Feed stored = storedFeed();
        when(feeds.findWithSourceById(9L)).thenReturn(Optional.of(stored));

        service.create(request());

        ArgumentCaptor<NewFeed> inserted = ArgumentCaptor.forClass(NewFeed.class);
        verify(inserter).insert(inserted.capture());
        assertThat(inserted.getValue().sourceId()).isEqualTo(4L);
        verify(sourceLock).acquire(3L);
        verify(sourceLock).acquire(4L);
    }

    @Test
    void anAutomaticallyResolvedSourceThatIsGoneAgainIsAConflictTheCallerCanRetry() {
        loads("Example", null);
        when(sources.exists(3L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(sources, org.mockito.Mockito.times(2)).resolveAutomatic(any(), any(), any(URI.class));
        verify(inserter, never()).insert(any());
    }
}
