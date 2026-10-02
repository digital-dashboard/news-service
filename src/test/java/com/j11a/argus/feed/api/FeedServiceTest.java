package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Instant;
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
    private final FeedService service = new FeedService(feeds, inserter, sources, loader, ingest);

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
        when(loader.load(any(URI.class), anyString())).thenReturn(new FeedLoader.Loaded.Parsed(parsed, FETCHED_AT));
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
}
