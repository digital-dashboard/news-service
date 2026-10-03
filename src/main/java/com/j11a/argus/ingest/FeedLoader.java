package com.j11a.argus.ingest;

import com.j11a.argus.feed.fetch.FeedFetcher;
import com.j11a.argus.feed.fetch.FetchResult;
import com.j11a.argus.feed.parse.FeedParseException;
import com.j11a.argus.feed.parse.FeedParser;
import com.j11a.argus.feed.parse.ParsedFeed;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Fetches and parses a feed. Neither step holds a database connection. */
@Component
public class FeedLoader {

    private static final String PARSE_SPAN = "argus.parse";

    public sealed interface Loaded {

        record Parsed(ParsedFeed feed, Instant fetchedAt) implements Loaded {
        }

        record NotModified(FetchResult.NotModified notModified) implements Loaded {
        }

        /** reason is the lowercase fetch or parse reason tag. */
        record Failed(String reason) implements Loaded {
        }
    }

    private final FeedFetcher fetcher;
    private final FeedParser parser;
    private final IngestTelemetry telemetry;
    private final Clock clock;

    public FeedLoader(FeedFetcher fetcher, FeedParser parser, IngestTelemetry telemetry, Clock clock) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public Loaded load(URI url, String sourceKey) {
        return switch (telemetry.fetch(sourceKey, () -> fetcher.fetch(url))) {
            case FetchResult.Failed failed -> new Loaded.Failed(failed.reason().tag());
            case FetchResult.NotModified notModified -> new Loaded.NotModified(notModified);
            case FetchResult.Fetched fetched -> parseFetched(fetched, clock.instant());
        };
    }

    private Loaded parseFetched(FetchResult.Fetched fetched, Instant fetchedAt) {
        try {
            ParsedFeed feed = telemetry.span(PARSE_SPAN, () -> parse(fetched));
            return new Loaded.Parsed(feed, fetchedAt);
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
