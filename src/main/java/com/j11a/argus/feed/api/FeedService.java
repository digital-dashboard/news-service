package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.security.AdminAccess;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceResolver;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.url.Links;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately not transactional: the download must not hold a connection. The source upsert and the feed insert
 * each commit on their own.
 */
@Service
public class FeedService {

    private static final Logger LOG = LoggerFactory.getLogger(FeedService.class);
    private static final String INVALID_DETAIL = "The URL did not return a readable feed.";
    /** feed.name is varchar(255). */
    static final int MAX_NAME_LENGTH = 255;
    /** feed.language is varchar(16). */
    static final int MAX_LANGUAGE_LENGTH = 16;

    private final FeedRepository feeds;
    private final FeedInserter inserter;
    private final SourceService sources;
    private final FeedLoader loader;
    private final FeedIngestService ingest;

    public FeedService(FeedRepository feeds, FeedInserter inserter, SourceService sources, FeedLoader loader,
            FeedIngestService ingest) {
        this.feeds = feeds;
        this.inserter = inserter;
        this.sources = sources;
        this.loader = loader;
        this.ingest = ingest;
    }

    public FeedResponse create(CreateFeedRequest request) {
        String url = Links.clean(request.url());
        if (url == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The feed URL must be an absolute http or https URL.");
        }
        inserter.findIdByUrl(url).ifPresent(existingId -> {
            throw conflict(Optional.of(existingId));
        });
        URI uri = URI.create(url);
        FeedLoader.Loaded.Parsed loaded = download(uri);
        ParsedFeed parsed = loaded.feed();
        Source source = sources.findOrCreate(SourceResolver.keyFor(parsed.siteLink(), uri), parsed.siteLink());
        long id = inserter.insert(new NewFeed(source.getId(), nameFor(request, parsed, source), url,
                        Links.clean(parsed.siteLink()), request.topic(), languageOf(parsed)))
                .orElseThrow(() -> conflict(inserter.findIdByUrl(url)));
        Feed feed = feeds.findWithSourceById(id).orElseThrow();
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, parsed, loaded.fetchedAt());
        } catch (RuntimeException e) {
            LOG.error("First ingest of feed {} failed; the feed was created and a refresh will retry", feed.getId(), e);
        }
        return FeedResponse.of(feed, AdminAccess.isAdmin());
    }

    public FeedResponse get(long id) {
        Feed feed = feeds.findWithSourceById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist."));
        return FeedResponse.of(feed, AdminAccess.isAdmin());
    }

    private FeedLoader.Loaded.Parsed download(URI uri) {
        // The source row does not exist yet, so the fetch is tagged with the feed-URL host, which can differ from the
        // site-link key that refreshes use.
        return switch (loader.load(uri, SourceResolver.keyFor(null, uri))) {
            case FeedLoader.Loaded.Parsed parsed -> parsed;
            case FeedLoader.Loaded.NotModified ignored ->
                    throw new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", "not_modified"));
            case FeedLoader.Loaded.Failed(var reason) ->
                    throw new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", reason));
        };
    }

    private static ApiException conflict(Optional<Long> existingId) {
        Map<String, Object> properties = existingId.<Map<String, Object>>map(id -> Map.of("existingFeedId", id))
                .orElse(Map.of());
        return new ApiException(ErrorCode.FEED_URL_CONFLICT, "A feed with this URL already exists.", properties);
    }

    private static String nameFor(CreateFeedRequest request, ParsedFeed parsed, Source source) {
        String requested = request.name();
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        return parsed.title().isBlank() ? source.getKey() : truncated(parsed.title());
    }

    // Counted in code points, as Postgres counts characters, so a surrogate pair is never split.
    private static String truncated(String title) {
        return title.codePointCount(0, title.length()) <= MAX_NAME_LENGTH
                ? title
                : title.substring(0, title.offsetByCodePoints(0, MAX_NAME_LENGTH));
    }

    private static @Nullable String languageOf(ParsedFeed parsed) {
        String language = parsed.language();
        return language != null && language.length() <= MAX_LANGUAGE_LENGTH ? language : null;
    }
}
