package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.fetch.FeedFetcher;
import com.j11a.argus.feed.fetch.FetchFailureReason;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.fetch.RetryingFeedFetcher;
import com.j11a.argus.feed.parse.FeedParseException;
import com.j11a.argus.feed.parse.FeedParser;
import com.j11a.argus.feed.parse.ParsedFeed;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedLoaderTest {

    private static final URI URL = URI.create("https://example.test/rss.xml");
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final byte[] BODY = "<rss/>".getBytes(StandardCharsets.UTF_8);
    private static final String SOURCE = "example.test";

    private final FeedFetcher fetcher = mock(FeedFetcher.class);
    private final RetryingFeedFetcher retrying = mock(RetryingFeedFetcher.class);
    private final FeedParser parser = mock(FeedParser.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FeedLoader loader = new FeedLoader(fetcher, retrying, parser,
            new IngestTelemetry(ObservationRegistry.create(), meters, Tracer.NOOP),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static FetchResult.Fetched fetched() {
        return new FetchResult.Fetched(BODY, "application/rss+xml", URL, null, new FetchValidators("\"v1\"", null));
    }

    private long createFetchTimers(String source, String outcome) {
        Timer timer = meters.find("argus.fetch").tag("source", source).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aFetchedFeedIsParsedWithTheNewValidators() throws FeedParseException {
        ParsedFeed parsed = new ParsedFeed("T", "https://example.test/", null, null, List.of());
        when(retrying.fetch(URL, FetchValidators.EMPTY, SOURCE)).thenReturn(fetched());
        when(parser.parse(any(), any(), any())).thenReturn(parsed);

        FeedLoader.Loaded loaded = loader.load(URL, FetchValidators.EMPTY, SOURCE);

        assertThat(loaded).isEqualTo(new FeedLoader.Loaded.Parsed(parsed, NOW, new FetchValidators("\"v1\"", null)));
    }

    @Test
    void aParseFailureIsAFailedLoadWithTheLowercaseReason() throws FeedParseException {
        when(retrying.fetch(URL, FetchValidators.EMPTY, SOURCE)).thenReturn(fetched());
        when(parser.parse(any(), any(), any())).thenThrow(new FeedParseException(FeedParseException.Reason.NOT_A_FEED));

        assertThat(loader.load(URL, FetchValidators.EMPTY, SOURCE)).isEqualTo(new FeedLoader.Loaded.Failed("not_a_feed"));
    }

    @Test
    void aNotModifiedAndAFailedFetchAreCarriedThrough() {
        FetchResult.NotModified notModified = new FetchResult.NotModified(URL, null, FetchValidators.EMPTY);
        when(retrying.fetch(URL, FetchValidators.EMPTY, SOURCE)).thenReturn(notModified)
                .thenReturn(new FetchResult.Failed(FetchFailureReason.HTTP_STATUS, 503));

        assertThat(loader.load(URL, FetchValidators.EMPTY, SOURCE)).isEqualTo(new FeedLoader.Loaded.NotModified(notModified));
        assertThat(loader.load(URL, FetchValidators.EMPTY, SOURCE))
                .isEqualTo(new FeedLoader.Loaded.Failed("http_status", 503));
    }

    @Test
    void theCreateLoadReturnsTheParsedFeedAndLeavesTheTimerRunningForTheCaller() throws FeedParseException {
        ParsedFeed parsed = new ParsedFeed("T", "https://example.test/", null, null, List.of());
        when(fetcher.fetch(URL)).thenReturn(fetched());
        when(parser.parse(any(), any(), any())).thenReturn(parsed);
        IngestTelemetry.CreateFetchTimer timer = loader.startCreateFetch();

        FeedLoader.CreateLoaded loaded = loader.loadForCreate(URL, timer);

        assertThat(loaded).isEqualTo(new FeedLoader.CreateLoaded.Created(parsed, NOW,
                new FetchValidators("\"v1\"", null), BODY.length));
        assertThat(createFetchTimers("unknown", "fetched")).isZero();
        timer.completed(SOURCE, BODY.length);
        assertThat(createFetchTimers(SOURCE, "fetched")).isEqualTo(1);
    }

    @Test
    void aFailedCreateFetchIsTimedUnderTheUnknownSource() {
        when(fetcher.fetch(URL)).thenReturn(new FetchResult.Failed(FetchFailureReason.TIMEOUT, null));

        FeedLoader.CreateLoaded loaded = loader.loadForCreate(URL, loader.startCreateFetch());

        assertThat(loaded).isEqualTo(new FeedLoader.CreateLoaded.Failed("timeout"));
        assertThat(createFetchTimers("unknown", "failed")).isEqualTo(1);
    }

    @Test
    void aNotModifiedAnswerOnTheCreatePathIsAFailure() {
        when(fetcher.fetch(URL)).thenReturn(new FetchResult.NotModified(URL, null, FetchValidators.EMPTY));

        FeedLoader.CreateLoaded loaded = loader.loadForCreate(URL, loader.startCreateFetch());

        assertThat(loaded).isEqualTo(new FeedLoader.CreateLoaded.Failed("not_modified"));
    }

    @Test
    void aCreateParseFailureIsTimedAsFetchedUnderTheUnknownSource() throws FeedParseException {
        when(fetcher.fetch(URL)).thenReturn(fetched());
        when(parser.parse(any(), any(), any())).thenThrow(new FeedParseException(FeedParseException.Reason.EMPTY));

        FeedLoader.CreateLoaded loaded = loader.loadForCreate(URL, loader.startCreateFetch());

        assertThat(loaded).isEqualTo(new FeedLoader.CreateLoaded.Failed("empty"));
        assertThat(createFetchTimers("unknown", "fetched")).isEqualTo(1);
    }
}
