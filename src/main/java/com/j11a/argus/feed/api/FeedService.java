package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.security.AdminAccess;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceResolver;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.LogSafe;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class FeedService {

    private static final String INVALID_DETAIL = "The URL did not return a readable feed.";
    /** feed.name is varchar(255). */
    static final int MAX_NAME_LENGTH = 255;
    /** feed.language is varchar(16). */
    static final int MAX_LANGUAGE_LENGTH = 16;
    private static final String DELETE_OWNED_ARTICLES = """
            DELETE FROM article a USING article_feed mine
            WHERE mine.feed_id = :feedId AND mine.article_id = a.id
              AND NOT EXISTS (SELECT 1 FROM article_feed o WHERE o.article_id = a.id AND o.feed_id <> :feedId)
            """;

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
    private final SourceLock sourceLock;

    public FeedService(FeedRepository feeds, FeedInserter inserter, SourceService sources, FeedLoader loader,
            FeedIngestService ingest, FeedHealthUpdater healthUpdater,
            FeedHealthGauges healthGauges, PollProperties properties, JdbcClient jdbc, Clock clock,
            SourceLock sourceLock) {
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
        this.sourceLock = sourceLock;
    }

    /**
     * Deliberately not transactional: the download must not hold a connection. The source upsert and the feed insert
     * each commit on their own. Retries and conditional GET do not apply here: the create path makes one attempt.
     */
    public FeedResponse create(CreateFeedRequest request) {
        String url = StoredUrls.clean(request.url());
        if (url == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The feed URL must be an absolute http or https URL.");
        }
        inserter.findIdByUrl(url).ifPresent(existingId -> {
            throw conflict(url, Optional.of(existingId));
        });
        Source explicitSource = request.sourceId() != null
                ? sources.findById(request.sourceId())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.SOURCE_NOT_FOUND, "Source " + request.sourceId() + " does not exist."))
                : null;
        URI uri = URI.create(url);
        IngestTelemetry.CreateFetchTimer timer = loader.startCreateFetch();
        FeedLoader.CreateLoaded.Created loaded = download(uri, timer);
        ParsedFeed parsed = loaded.feed();
        Source source = explicitSource != null
                ? explicitSource
                : sources.findOrCreate(SourceResolver.keyFor(parsed.siteLink(), uri), parsed.siteLink());
        timer.completed(source.getKey(), loaded.bodyLength());
        long id = inserter.insert(new NewFeed(source.getId(), nameFor(request, parsed, source), url,
                        StoredUrls.cleanPublic(parsed.siteLink()), request.topic(), languageOf(parsed)))
                .orElseThrow(() -> conflict(url, inserter.findIdByUrl(url)));
        Feed feed = requireFeed(id);
        logCreated(feed, source);
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, parsed, loaded.fetchedAt());
            healthUpdater.recordSuccess(feed.getId(), loaded.validators(), clock.instant());
        } catch (RuntimeException e) {
            log.atError()
                    .setMessage("First ingest of feed " + feed.getId()
                            + " failed; the feed was created and a refresh will retry")
                    .addKeyValue(LogKeys.FEED_ID, feed.getId())
                    .setCause(e)
                    .log();
            healthUpdater.recordFailure(feed.getId(), FailureReasons.FIRST_INGEST_FAILED, clock.instant());
        } finally {
            healthGauges.refreshAfterCommit();
        }
        return toResponse(feed);
    }

    public FeedResponse get(long id) {
        return toResponse(requireFeed(id));
    }

    @Transactional(readOnly = true)
    public Page<FeedResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").ascending());
        return feeds.findAll(pageRequest).map(this::toResponse);
    }

    /** One atomic UPDATE, so a health write that lands meanwhile is never reverted by a stale entity save. */
    @Transactional
    public FeedResponse patch(long id, PatchFeedRequest request) {
        int updated = jdbc.sql("UPDATE feed SET enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("enabled", request.enabled())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("id", id)
                .update();
        if (updated == 0) {
            throw notFound(id);
        }
        healthGauges.refreshAfterCommit();
        log.atInfo()
                .setMessage(feedText(id, request.enabled() ? "enabled" : "disabled"))
                .addKeyValue(LogKeys.FEED_ID, id)
                .addKeyValue(LogKeys.ENABLED, request.enabled())
                .log();
        return toResponse(requireFeed(id));
    }

    /** Removes the feed and the articles only it linked to, in one transaction. The source row is kept. */
    @Transactional
    public void delete(long id) {
        Long sourceId = jdbc.sql("SELECT source_id FROM feed WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> notFound(id));
        // Serialises with ingest so a delete cannot race an article_feed insert for the same source.
        sourceLock.acquire(sourceId);
        int removedArticles = jdbc.sql(DELETE_OWNED_ARTICLES).param("feedId", id).update();
        int deleted = jdbc.sql("DELETE FROM feed WHERE id = :id").param("id", id).update();
        if (deleted == 0) {
            throw notFound(id);
        }
        healthGauges.refreshAfterCommit();
        log.atInfo()
                .setMessage(feedText(id, "deleted along with " + removedArticles + " articles"))
                .addKeyValue(LogKeys.FEED_ID, id)
                .addKeyValue(LogKeys.SOURCE_ID, sourceId)
                .addKeyValue(LogKeys.ARTICLES_REMOVED, removedArticles)
                .log();
    }

    private Feed requireFeed(long id) {
        return feeds.findWithSourceById(id).orElseThrow(() -> notFound(id));
    }

    private FeedResponse toResponse(Feed feed) {
        return FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    private static ApiException notFound(long id) {
        return new ApiException(ErrorCode.FEED_NOT_FOUND, feedText(id, "does not exist."));
    }

    private static String feedText(long id, String what) {
        return "Feed " + id + " " + what;
    }

    private FeedLoader.CreateLoaded.Created download(URI uri, IngestTelemetry.CreateFetchTimer timer) {
        return switch (loader.loadForCreate(uri, timer)) {
            case FeedLoader.CreateLoaded.Created created -> created;
            case FeedLoader.CreateLoaded.Failed failed -> throw rejected(uri, failed);
        };
    }

    private ApiException rejected(URI uri, FeedLoader.CreateLoaded.Failed failed) {
        String url = HttpUrls.redact(uri.toString());
        FetchError error = failed.error();
        String detail = failed.reason() + (error != null ? " " + error.type() : "");
        LoggingEventBuilder event = log.atWarn()
                .setMessage("Feed creation rejected for " + url + ": " + detail)
                .addKeyValue(LogKeys.URL, url)
                .addKeyValue(LogKeys.REASON, failed.reason());
        FetchError.addFields(event, error, failed.contentType(), failed.bodyBytes()).log();
        return new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", failed.reason()));
    }

    private void logCreated(Feed feed, Source source) {
        String url = HttpUrls.redact(feed.getUrl());
        log.atInfo()
                .setMessage(feedText(feed.getId(), "created: " + LogSafe.sanitize(feed.getName(), MAX_NAME_LENGTH)
                        + " (" + source.getKey() + ", topic " + feed.getTopic() + ") from " + url))
                .addKeyValue(LogKeys.FEED_ID, feed.getId())
                .addKeyValue(LogKeys.SOURCE_ID, source.getId())
                .addKeyValue(LogKeys.SOURCE_KEY, source.getKey())
                .addKeyValue(LogKeys.URL, url)
                .log();
    }

    private ApiException conflict(String url, Optional<Long> existingId) {
        String redacted = HttpUrls.redact(url);
        LoggingEventBuilder event = log.atInfo()
                .setMessage("Feed creation conflicts with an existing feed for " + redacted)
                .addKeyValue(LogKeys.URL, redacted);
        LogFields.put(event, LogKeys.EXISTING_FEED_ID, existingId.orElse(null));
        event.log();
        Map<String, Object> problemProperties = existingId.<Map<String, Object>>map(id -> Map.of("existingFeedId", id))
                .orElse(Map.of());
        return new ApiException(ErrorCode.FEED_URL_CONFLICT, "A feed with this URL already exists.", problemProperties);
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
