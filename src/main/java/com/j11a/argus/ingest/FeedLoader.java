package com.j11a.argus.ingest;

import com.j11a.argus.feed.fetch.FeedFetcher;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.fetch.FetchValidators;
import com.j11a.argus.feed.fetch.RetryingFeedFetcher;
import com.j11a.argus.feed.parse.FeedParseException;
import com.j11a.argus.feed.parse.FeedParser;
import com.j11a.argus.feed.parse.ParsedFeed;
import io.micrometer.core.instrument.Timer;
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

        record Parsed(ParsedFeed feed, Instant fetchedAt, FetchValidators validators) implements Loaded {
            public Parsed(ParsedFeed feed, Instant fetchedAt) {
                this(feed, fetchedAt, FetchValidators.EMPTY);
            }
        }

        record CreateParsed(ParsedFeed feed, Instant fetchedAt, FetchValidators validators, int bodyLength,
                            Timer.Sample sample) implements Loaded {
        }

        record NotModified(FetchResult.NotModified notModified) implements Loaded {
        }

        /** reason is the lowercase fetch or parse reason tag. */
        record Failed(String reason, @Nullable Integer httpStatus) implements Loaded {
            public Failed(String reason) {
                this(reason, null);
            }

            public String lastError() {
                return httpStatus != null ? reason + " " + httpStatus : reason;
            }
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

    public Loaded load(URI url, String sourceKey) {
        return load(url, FetchValidators.EMPTY, sourceKey);
    }

    public Loaded load(URI url, FetchValidators validators, String sourceKey) {
        return switch (telemetry.fetch(sourceKey, () -> retryingFetcher.fetch(url, validators, sourceKey))) {
            case FetchResult.Failed failed -> new Loaded.Failed(failed.reason().tag(), failed.httpStatus());
            case FetchResult.NotModified notModified -> new Loaded.NotModified(notModified);
            case FetchResult.Fetched fetched -> parseFetched(fetched, clock.instant());
        };
    }

    public Loaded loadForCreate(URI url) {
        Timer.Sample sample = telemetry.startTimerSample();
        FetchResult result = telemetry.span(FETCH_SPAN, () -> fetcher.fetch(url));
        return switch (result) {
            case FetchResult.Failed failed -> {
                telemetry.recordFailedCreateFetch(sample, failed.reason().tag());
                yield new Loaded.Failed(failed.reason().tag(), failed.httpStatus());
            }
            case FetchResult.NotModified notModified -> {
                telemetry.recordFailedCreateFetch(sample, "not_modified");
                yield new Loaded.Failed("not_modified");
            }
            case FetchResult.Fetched fetched -> {
                try {
                    ParsedFeed feed = telemetry.span(PARSE_SPAN, () -> parse(fetched));
                    yield new Loaded.CreateParsed(feed, clock.instant(), fetched.validators(),
                            fetched.body().length, sample);
                } catch (ParseFailure e) {
                    telemetry.recordFailedCreateParse(sample);
                    yield new Loaded.Failed(e.reason);
                }
            }
        };
    }

    public void completeCreateTelemetry(Loaded.CreateParsed loaded, String sourceKey) {
        telemetry.recordCreateFetch(loaded.sample(), sourceKey, loaded.bodyLength());
    }

    private Loaded parseFetched(FetchResult.Fetched fetched, Instant fetchedAt) {
        try {
            ParsedFeed feed = telemetry.span(PARSE_SPAN, () -> parse(fetched));
            return new Loaded.Parsed(feed, fetchedAt, fetched.validators());
        } catch (ParseFailure e) {
            return new Loaded.Failed(e.reason);
        }
    }

    private ParsedFeed parse(FetchResult.Fetched fetched) {
        try {
            return parser.parse(fetched.body(), fetched.finalUrl(), fetched.contentType());
        } catch (FeedParseException e) {
            throw new ParseFailure(e.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final String reason;

        ParseFailure(String reason) {
            super("Feed could not be parsed: " + reason);
            this.reason = reason;
        }
    }
}
