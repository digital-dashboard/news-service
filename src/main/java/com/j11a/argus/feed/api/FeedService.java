package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
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
import com.j11a.argus.config.PollProperties;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final PollProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    public FeedService(FeedRepository feeds, FeedInserter inserter, SourceService sources, FeedLoader loader,
            FeedIngestService ingest, FeedHealthUpdater healthUpdater,
            FeedHealthGauges healthGauges, PollProperties properties, JdbcClient jdbc, Clock clock) {
        this.feeds = feeds;
        this.inserter = inserter;
        this.sources = sources;
        this.loader = loader;
        this.ingest = ingest;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
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
        FeedLoader.Loaded.CreateParsed loaded = download(uri);
        ParsedFeed parsed = loaded.feed();
        Source source = sources.findOrCreate(SourceResolver.keyFor(parsed.siteLink(), uri), parsed.siteLink());
        loader.completeCreateTelemetry(loaded, source.getKey());
        long id = inserter.insert(new NewFeed(source.getId(), nameFor(request, parsed, source), url,
                        Links.clean(parsed.siteLink()), request.topic(), languageOf(parsed)))
                .orElseThrow(() -> conflict(inserter.findIdByUrl(url)));
        Feed feed = feeds.findWithSourceById(id).orElseThrow();
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, parsed, loaded.fetchedAt());
            healthUpdater.recordSuccess(feed.getId(), loaded.validators(), clock.instant());
        } catch (RuntimeException e) {
            LOG.error("First ingest of feed {} failed; the feed was created and a refresh will retry", feed.getId(), e);
            healthUpdater.recordFailure(feed.getId(), "first_ingest_failed", clock.instant());
        } finally {
            healthGauges.refresh();
        }
        return FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    public FeedResponse get(long id) {
        Feed feed = feeds.findWithSourceById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist."));
        return FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    @Transactional(readOnly = true)
    public Page<FeedResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").ascending());
        return feeds.findAll(pageRequest)
                .map(feed -> FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold()));
    }

    @Transactional
    public FeedResponse patch(long id, PatchFeedRequest request) {
        Feed feed = feeds.findWithSourceById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist."));
        feed.setEnabled(request.enabled());
        Feed saved = feeds.save(feed);
        healthGauges.refresh();
        return FeedResponse.of(saved, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    @Transactional
    public void delete(long id) {
        Feed feed = feeds.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist."));
        List<Long> articleIds = jdbc.sql("SELECT article_id FROM article_feed WHERE feed_id = :feedId")
                .param("feedId", id)
                .query(Long.class)
                .list();
        feeds.delete(feed);
        feeds.flush();
        if (!articleIds.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM article
                    WHERE id IN (:ids)
                      AND NOT EXISTS (SELECT 1 FROM article_feed af WHERE af.article_id = article.id)
                    """)
                    .param("ids", articleIds)
                    .update();
        }
        healthGauges.refresh();
    }

    private FeedLoader.Loaded.CreateParsed download(URI uri) {
        return switch (loader.loadForCreate(uri)) {
            case FeedLoader.Loaded.CreateParsed parsed -> parsed;
            case FeedLoader.Loaded.Failed(var reason, var ignored) ->
                    throw new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", reason));
            default -> throw new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", "unknown"));
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
