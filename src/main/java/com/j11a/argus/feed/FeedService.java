package com.j11a.argus.feed;

import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.ingest.EntryKeys;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceResolver;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately not transactional: the download must not hold a connection. The source upsert and the feed insert
 * each commit on their own.
 */
@Service
public class FeedService {

    private static final Logger LOG = LoggerFactory.getLogger(FeedService.class);
    private static final String FEED_URL_CONSTRAINT = "uq_feed_url";
    private static final String INVALID_DETAIL = "The URL did not return a readable feed.";

    private final FeedRepository feeds;
    private final SourceService sources;
    private final FeedLoader loader;
    private final FeedIngestService ingest;
    private final Clock clock;

    public FeedService(FeedRepository feeds, SourceService sources, FeedLoader loader, FeedIngestService ingest,
            Clock clock) {
        this.feeds = feeds;
        this.sources = sources;
        this.loader = loader;
        this.ingest = ingest;
        this.clock = clock;
    }

    public FeedResponse create(CreateFeedRequest request) {
        String url = EntryKeys.cleanLink(request.url());
        if (url == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The feed URL must be an absolute http or https URL.");
        }
        feeds.findByUrl(url).ifPresent(existing -> {
            throw conflict(Optional.of(existing.getId()));
        });
        FeedLoader.Loaded.Parsed loaded = download(url);
        ParsedFeed parsed = loaded.feed();
        Source source = sources.findOrCreate(SourceResolver.keyFor(parsed.siteLink(), URI.create(url)),
                parsed.siteLink());
        Feed feed = save(new Feed(source, nameFor(request, parsed, source), url,
                EntryKeys.cleanLink(parsed.siteLink()), request.topic(), parsed.language(), clock.instant()));
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, parsed, loaded.fetchedAt());
        } catch (RuntimeException e) {
            LOG.error("First ingest of feed {} failed; the feed was created and a refresh will retry", feed.getId(), e);
        }
        return FeedResponse.of(feed);
    }

    public FeedResponse get(long id) {
        return FeedResponse.of(feeds.findWithSourceById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist.")));
    }

    private FeedLoader.Loaded.Parsed download(String url) {
        URI uri = URI.create(url);
        FeedLoader.Loaded loaded = loader.load(uri, SourceResolver.keyFor(null, uri));
        if (loaded instanceof FeedLoader.Loaded.Failed failed) {
            throw new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", failed.reason()));
        }
        return (FeedLoader.Loaded.Parsed) loaded;
    }

    private Feed save(Feed feed) {
        try {
            return feeds.saveAndFlush(feed);
        } catch (DataIntegrityViolationException e) {
            if (!isFeedUrlViolation(e)) {
                throw e;
            }
            throw conflict(feeds.findByUrl(feed.getUrl()).map(Feed::getId));
        }
    }

    private static boolean isFeedUrlViolation(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(FEED_URL_CONSTRAINT);
    }

    private static ApiException conflict(Optional<Long> existingId) {
        Map<String, Object> properties = existingId.<Map<String, Object>>map(id -> Map.of("existingFeedId", id))
                .orElse(Map.of());
        return new ApiException(ErrorCode.FEED_URL_CONFLICT, "A feed with this URL already exists.", properties);
    }

    private static String nameFor(CreateFeedRequest request, ParsedFeed parsed, Source source) {
        if (request.name() != null && !request.name().isBlank()) {
            return request.name().strip();
        }
        return parsed.title().isBlank() ? source.getKey() : parsed.title();
    }
}
