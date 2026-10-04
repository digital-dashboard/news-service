package com.j11a.argus.ingest;

import com.j11a.argus.feed.fetch.FeedFetcher;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.fetch.RetryingFeedFetcher;
import com.j11a.argus.feed.parse.FeedParseException;
import com.j11a.argus.feed.parse.FeedParser;
import com.j11a.argus.feed.parse.ParsedFeed;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Fetches and parses a feed. Neither step holds a database connection. */
@Component
public class FeedLoader {

    private static final String PARSE_SPAN = "argus.parse";
    private static final String FETCH_SPAN = "argus.fetch";

    public sealed interface Loaded {

        /** permanentTarget is where an unbroken 301/308 chain from the stored URL ended, if it did. */
        record Parsed(ParsedFeed feed, Instant fetchedAt, FetchValidators validators, @Nullable URI permanentTarget)
                implements Loaded {
        }

        record NotModified(FetchResult.NotModified notModified) implements Loaded {
        }

        /**
         * reason is the lowercase fetch or parse reason tag. error, contentType and bodyBytes are for logs only; the
         * last two are set for parse failures.
         */
        record Failed(String reason, @Nullable Integer httpStatus, @Nullable FetchError error,
                      @Nullable String contentType, @Nullable Integer bodyBytes) implements Loaded {

            public String lastError() {
                return httpStatus != null ? reason + " " + httpStatus : reason;
            }
        }
    }

    /** The result of the first download on the create path, which has no stored feed, source or validators yet. */
    public sealed interface CreateLoaded {

        /** finalUrl is where the download ended, after any redirects. */
        record Created(ParsedFeed feed, Instant fetchedAt, FetchValidators validators, int bodyLength, URI finalUrl)
                implements CreateLoaded {
        }

        record Failed(String reason, @Nullable FetchError error, @Nullable String contentType,
                      @Nullable Integer bodyBytes) implements CreateLoaded {
        }
    }

    private final FeedFetcher fetcher;
    private final RetryingFeedFetcher retryingFetcher;
    private final FeedParser parser;
    private final IngestTelemetry telemetry;
    private final Clock clock;

    public FeedLoader(FeedFetcher fetcher, RetryingFeedFetcher retryingFetcher, FeedParser parser,
            IngestTelemetry telemetry, Clock clock) {
        this.fetcher = fetcher;
        this.retryingFetcher = retryingFetcher;
        this.parser = parser;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public Loaded load(URI url, FetchValidators validators, String sourceKey) {
        return switch (telemetry.fetch(sourceKey, () -> retryingFetcher.fetch(url, validators, sourceKey))) {
            case FetchResult.Failed(var reason, var httpStatus, var error) ->
                    new Loaded.Failed(reason.tag(), httpStatus, error, null, null);
            case FetchResult.NotModified notModified -> new Loaded.NotModified(notModified);
            case FetchResult.Fetched fetched -> parseFetched(fetched, clock.instant());
        };
    }

    public IngestTelemetry.CreateFetchTimer startCreateFetch() {
        return telemetry.startCreateFetch();
    }

    public CreateLoaded loadForCreate(URI url, IngestTelemetry.CreateFetchTimer timer) {
        return switch (telemetry.span(FETCH_SPAN, () -> fetcher.fetch(url))) {
            case FetchResult.Failed failed -> {
                timer.failed(failed.reason().tag());
                yield new CreateLoaded.Failed(failed.reason().tag(), failed.error(), null, null);
            }
            case FetchResult.NotModified ignored -> {
                timer.failed(IngestTelemetry.NOT_MODIFIED);
                yield new CreateLoaded.Failed(IngestTelemetry.NOT_MODIFIED, null, null, null);
            }
            case FetchResult.Fetched fetched -> parseForCreate(fetched, timer);
        };
    }

    private CreateLoaded parseForCreate(FetchResult.Fetched fetched, IngestTelemetry.CreateFetchTimer timer) {
        try {
            ParsedFeed feed = telemetry.span(PARSE_SPAN, () -> parse(fetched));
            return new CreateLoaded.Created(feed, clock.instant(), fetched.validators(), fetched.body().length,
                    fetched.finalUrl());
        } catch (ParseFailure e) {
            timer.parseFailed();
            return new CreateLoaded.Failed(e.reason, e.error, e.contentType, e.bodyBytes);
        }
    }

    private Loaded parseFetched(FetchResult.Fetched fetched, Instant fetchedAt) {
        try {
            ParsedFeed feed = telemetry.span(PARSE_SPAN, () -> parse(fetched));
            return new Loaded.Parsed(feed, fetchedAt, fetched.validators(), fetched.permanentTarget());
        } catch (ParseFailure e) {
            return new Loaded.Failed(e.reason, null, e.error, e.contentType, e.bodyBytes);
        }
    }

    private ParsedFeed parse(FetchResult.Fetched fetched) {
        try {
            return parser.parse(fetched.body(), fetched.finalUrl(), fetched.contentType());
        } catch (FeedParseException e) {
            throw new ParseFailure(e, fetched);
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final String reason;
        private final transient FetchError error;
        private final @Nullable String contentType;
        private final int bodyBytes;

        ParseFailure(FeedParseException cause, FetchResult.Fetched fetched) {
            super("Feed could not be parsed: " + cause.reason());
            this.reason = cause.reason().name().toLowerCase(Locale.ROOT);
            this.error = FetchError.ofMessage(FeedParseException.class.getSimpleName(), cause.getMessage());
            this.contentType = fetched.contentType();
            this.bodyBytes = fetched.body().length;
        }
    }
}
