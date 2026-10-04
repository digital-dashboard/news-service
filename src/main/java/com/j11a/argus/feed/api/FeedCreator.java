package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.identity.FeedIdentityRegistry.Candidate;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.LogSafe;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Registers a feed: the checks and the download, then one short transaction that places it. Deliberately not
 * transactional itself: the download must not hold a connection. The entered URL is checked before the download as a
 * fast fail; after it, the identity checks, the source resolution and the insert share a transaction under the
 * identity lock, so no other create or URL change can slip in between. The source is then locked, so a merge or move
 * cannot delete it before the insert. Identity first, then source: no path takes them the other way round. A conflict
 * rolls a source created here back with it. Retries and conditional GET do not apply: the create path makes one
 * attempt.
 */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FeedCreator {

    private static final String CREATION = "creation";

    /** The feed as stored, and the download its first ingest reuses. */
    record Created(Feed feed, FeedLoader.CreateLoaded.Created download) {
    }

    private record Placed(long feedId, Source source) {
    }

    private final FeedRepository feeds;
    private final FeedInserter inserter;
    private final SourceService sources;
    private final SourceLock sourceLock;
    private final FeedUrlChecks urlChecks;

    FeedCreator(FeedRepository feeds, FeedInserter inserter, SourceService sources, SourceLock sourceLock,
            FeedUrlChecks urlChecks) {
        this.feeds = feeds;
        this.inserter = inserter;
        this.sources = sources;
        this.sourceLock = sourceLock;
        this.urlChecks = urlChecks;
    }

    Created create(CreateFeedRequest request) {
        String url = StoredUrls.clean(request.url());
        if (url == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The feed URL must be an absolute http or https URL.");
        }
        urlChecks.requireFree(CREATION, List.of(FeedUrlChecks.entered(url)), null);
        Long sourceId = request.sourceId();
        Source explicitSource = sourceId == null
                ? null
                : sources.findById(sourceId).orElseThrow(() -> ApiException.sourceNotFound(sourceId));
        IngestTelemetry.CreateFetchTimer timer = urlChecks.startTimer();
        FeedLoader.CreateLoaded.Created download = urlChecks.download(CREATION, URI.create(url), timer);
        Placed placed = urlChecks.underIdentityLock(() -> place(request, url, download, explicitSource, timer));
        Feed feed = feeds.findWithSourceById(placed.feedId())
                .orElseThrow(() -> ApiException.feedNotFound(placed.feedId()));
        logCreated(feed, placed.source());
        return new Created(feed, download);
    }

    private Placed place(CreateFeedRequest request, String url, FeedLoader.CreateLoaded.Created download,
            @Nullable Source explicitSource, IngestTelemetry.CreateFetchTimer timer) {
        ParsedFeed parsed = download.feed();
        List<Candidate> candidates = FeedUrlChecks.candidates(url, download.finalUrl(), parsed.selfLink());
        urlChecks.requireFree(CREATION, candidates, null);
        Source source = explicitSource != null ? lockedExplicit(explicitSource) : lockedAutomatic(parsed, url);
        timer.completed(source.getKey(), download.bodyLength());
        NewFeed newFeed = new NewFeed(source.getId(), nameFor(request, parsed, source), url,
                StoredUrls.cleanPublic(parsed.siteLink()), StoredUrls.clean(parsed.selfLink()), request.topic(),
                languageOf(parsed));
        long id = inserter.insert(newFeed).orElseThrow(() -> urlChecks.lostRace(CREATION, candidates, null));
        return new Placed(id, source);
    }

    private Source lockedExplicit(Source source) {
        if (!lockedAndPresent(source)) {
            throw ApiException.sourceNotFound(source.getId());
        }
        return source;
    }

    /** A source merged away before it could be locked is resolved again, once. */
    private Source lockedAutomatic(ParsedFeed parsed, String url) {
        Source resolved = sources.resolveAutomatic(parsed.siteLink(), parsed.selfLink(), URI.create(url));
        if (lockedAndPresent(resolved)) {
            return resolved;
        }
        Source again = sources.resolveAutomatic(parsed.siteLink(), parsed.selfLink(), URI.create(url));
        if (lockedAndPresent(again)) {
            return again;
        }
        throw new ApiException(ErrorCode.CONFLICT,
                "The source of the new feed was merged away while it was being created; retry.");
    }

    /** Only the lock makes the existence check binding: a merge or move holds it while it deletes the source. */
    private boolean lockedAndPresent(Source source) {
        sourceLock.acquire(source.getId());
        return sources.exists(source.getId());
    }

    private void logCreated(Feed feed, Source source) {
        String url = HttpUrls.redact(feed.getUrl());
        log.atInfo()
                .setMessage(FeedService.feedText(feed.getId(), "created: "
                        + LogSafe.sanitize(feed.getName(), FeedService.MAX_NAME_LENGTH)
                        + " (" + source.getKey() + ", topic " + feed.getTopic() + ") from " + url))
                .addKeyValue(LogKeys.FEED_ID, feed.getId())
                .addKeyValue(LogKeys.SOURCE_ID, source.getId())
                .addKeyValue(LogKeys.SOURCE_KEY, source.getKey())
                .addKeyValue(LogKeys.URL, url)
                .log();
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
        return title.codePointCount(0, title.length()) <= FeedService.MAX_NAME_LENGTH
                ? title
                : title.substring(0, title.offsetByCodePoints(0, FeedService.MAX_NAME_LENGTH));
    }

    private static @Nullable String languageOf(ParsedFeed parsed) {
        String language = parsed.language();
        return language != null && language.length() <= FeedService.MAX_LANGUAGE_LENGTH ? language : null;
    }
}
