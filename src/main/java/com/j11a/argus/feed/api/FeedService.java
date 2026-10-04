package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.health.FeedHealthUpdater;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.feed.parse.ParsedFeed;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.ingest.FailureReasons;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.security.AdminAccess;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.LogSafe;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class FeedService {

    /** The feed API's audit lines share one logger, whichever of its collaborators writes them. */
    static final String AUDIT_LOGGER = "com.j11a.argus.feed.api.FeedService";
    private static final String CREATION = "creation";
    /** feed.name is varchar(255). */
    static final int MAX_NAME_LENGTH = 255;
    /** feed.language is varchar(16). */
    static final int MAX_LANGUAGE_LENGTH = 16;

    private record Placed(long feedId, Source source) {
    }

    private final FeedRepository feeds;
    private final FeedInserter inserter;
    private final SourceService sources;
    private final FeedProbe probe;
    private final FeedUrlChecks urlChecks;
    private final FeedIdentityRegistry registry;
    private final FeedUpdater updater;
    private final FeedRemover remover;
    private final FeedIngestService ingest;
    private final FeedHealthUpdater healthUpdater;
    private final FeedHealthGauges healthGauges;
    private final PollProperties properties;
    private final Clock clock;

    public FeedService(FeedRepository feeds, FeedInserter inserter, SourceService sources, FeedProbe probe,
            FeedUrlChecks urlChecks, FeedIdentityRegistry registry, FeedUpdater updater, FeedRemover remover,
            FeedIngestService ingest, FeedHealthUpdater healthUpdater, FeedHealthGauges healthGauges,
            PollProperties properties, Clock clock) {
        this.feeds = feeds;
        this.inserter = inserter;
        this.sources = sources;
        this.probe = probe;
        this.urlChecks = urlChecks;
        this.registry = registry;
        this.updater = updater;
        this.remover = remover;
        this.ingest = ingest;
        this.healthUpdater = healthUpdater;
        this.healthGauges = healthGauges;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Deliberately not transactional: the download must not hold a connection. The entered URL is checked before the
     * download as a fast fail; after it, the identity checks, the source resolution and the insert share one short
     * transaction under the identity lock, so no other create or URL change can slip in between. Source resolution
     * is a couple of indexed reads and at most one insert, so holding the lock across it is cheap, and a conflict
     * rolls the new source back with it. Retries and conditional GET do not apply: the create path makes one attempt.
     */
    public FeedResponse create(CreateFeedRequest request) {
        String url = StoredUrls.clean(request.url());
        if (url == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The feed URL must be an absolute http or https URL.");
        }
        urlChecks.requireFree(CREATION, List.of(FeedUrlChecks.entered(url)), null);
        Source explicitSource = request.sourceId() != null
                ? sources.findById(request.sourceId())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.SOURCE_NOT_FOUND, "Source " + request.sourceId() + " does not exist."))
                : null;
        URI uri = URI.create(url);
        IngestTelemetry.CreateFetchTimer timer = probe.startTimer();
        FeedLoader.CreateLoaded.Created loaded = probe.download(CREATION, uri, timer);
        Placed placed = registry.withIdentityLock(() -> place(request, url, loaded, explicitSource, timer));
        Feed feed = requireFeed(placed.feedId());
        logCreated(feed, placed.source());
        firstIngest(feed, loaded);
        return toResponse(feed);
    }

    private Placed place(CreateFeedRequest request, String url, FeedLoader.CreateLoaded.Created loaded,
            @Nullable Source explicitSource, IngestTelemetry.CreateFetchTimer timer) {
        ParsedFeed parsed = loaded.feed();
        List<FeedIdentityRegistry.Candidate> candidates = FeedUrlChecks.candidates(
                url, loaded.finalUrl(), parsed.selfLink());
        urlChecks.requireFree(CREATION, candidates, null);
        Source source = explicitSource != null
                ? explicitSource
                : sources.resolveAutomatic(parsed.siteLink(), parsed.selfLink(), URI.create(url));
        timer.completed(source.getKey(), loaded.bodyLength());
        NewFeed newFeed = new NewFeed(source.getId(), nameFor(request, parsed, source), url,
                StoredUrls.cleanPublic(parsed.siteLink()), StoredUrls.clean(parsed.selfLink()), request.topic(),
                languageOf(parsed));
        long id = inserter.insert(newFeed).orElseThrow(() -> lostInsertRace(url, candidates));
        return new Placed(id, source);
    }

    // The identity lock makes this nearly impossible; the unique indexes still decide if a writer ignored the lock.
    private ApiException lostInsertRace(String url, List<FeedIdentityRegistry.Candidate> candidates) {
        return registry.findConflict(candidates, null)
                .map(conflict -> urlChecks.conflict(CREATION, url, conflict.kind(), conflict.existingFeedId()))
                .orElseGet(() -> urlChecks.conflict(CREATION, url, IdentityKind.ENTERED, null));
    }

    private void firstIngest(Feed feed, FeedLoader.CreateLoaded.Created loaded) {
        // The feed is committed, so a failed first ingest must not turn a successful create into an error.
        try {
            ingest.ingestParsed(feed, loaded.feed(), loaded.fetchedAt());
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
    }

    public FeedResponse get(long id) {
        return toResponse(requireFeed(id));
    }

    @Transactional(readOnly = true)
    public Page<FeedResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").ascending());
        return feeds.findAll(pageRequest).map(this::toResponse);
    }

    /** See FeedUpdater for the order of the steps and what a failure part-way leaves behind. */
    public FeedResponse patch(long id, PatchFeedRequest request) {
        updater.patch(id, request);
        return toResponse(requireFeed(id));
    }

    /** Removes the feed and the articles only it linked to, in one transaction. The source row is kept. */
    public void delete(long id) {
        remover.delete(id);
    }

    private Feed requireFeed(long id) {
        return feeds.findWithSourceById(id).orElseThrow(() -> notFound(id));
    }

    private FeedResponse toResponse(Feed feed) {
        return FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    static ApiException notFound(long id) {
        return new ApiException(ErrorCode.FEED_NOT_FOUND, feedText(id, "does not exist."));
    }

    static String feedText(long id, String what) {
        return "Feed " + id + " " + what;
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
