package com.j11a.argus.feed.api;

import com.j11a.argus.config.Clocks;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.feed.identity.FeedIdentityRegistry;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.LogSafe;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Applies a PATCH to a feed. Not transactional: the URL download must not hold a connection, and each step commits
 * on its own, in this order: URL, then name, topic and enabled in one UPDATE, then the source move.
 *
 * Everything that can be refused is refused before the first write: the request shape, the feed, the target source,
 * the entered URL's identity and the download. Only a conflict that appears while the request runs (another create,
 * another PATCH, a concurrent move) can still fail a later step, and then the earlier steps stay applied.
 */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FeedUpdater {

    private static final String UPDATING = "update";
    private static final String UPDATE_URL = """
            UPDATE feed SET url = :url, self_url = :selfUrl, etag = NULL, last_modified = NULL, updated_at = :now
            WHERE id = :id
            """;
    // COALESCE keeps every column that is not being changed, so a health write that lands meanwhile is not reverted.
    private static final String UPDATE_FIELDS = """
            UPDATE feed
            SET name = COALESCE(:name, name),
                topic = COALESCE(:topic, topic),
                enabled = COALESCE(:enabled, enabled),
                updated_at = :now
            WHERE id = :id
            """;

    private record UrlChange(String url, @Nullable String selfUrl, FeedLoader.CreateLoaded.Created download) {
    }

    private final FeedRepository feeds;
    private final SourceService sources;
    private final SourceMerger merger;
    private final FeedUrlChecks urlChecks;
    private final FeedHealthGauges healthGauges;
    private final JdbcClient jdbc;
    private final Clock clock;

    FeedUpdater(FeedRepository feeds, SourceService sources, SourceMerger merger, FeedUrlChecks urlChecks,
            FeedHealthGauges healthGauges, JdbcClient jdbc, Clock clock) {
        this.feeds = feeds;
        this.sources = sources;
        this.merger = merger;
        this.urlChecks = urlChecks;
        this.healthGauges = healthGauges;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void patch(long id, PatchFeedRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validationFailed("request", "at least one field must be provided");
        }
        String name = strippedName(request.name());
        Feed feed = feeds.findWithSourceById(id).orElseThrow(() -> ApiException.feedNotFound(id));
        Long targetSourceId = request.sourceId();
        if (targetSourceId != null && sources.findById(targetSourceId).isEmpty()) {
            throw ApiException.sourceNotFound(targetSourceId);
        }
        UrlChange urlChange = prepareUrlChange(feed, request.url());

        if (urlChange != null) {
            changeUrl(feed, urlChange);
        }
        updateFields(id, name, request.topic(), request.enabled());
        if (targetSourceId != null) {
            SourceChangeRetry.twice(id, "moved", () -> merger.moveFeed(id, targetSourceId));
        }
    }

    private static @Nullable String strippedName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            throw ApiException.validationFailed("name", "must not be blank");
        }
        return stripped;
    }

    /** The entered-URL check and the download: null when the URL is not changing. */
    private @Nullable UrlChange prepareUrlChange(Feed feed, @Nullable String requestedUrl) {
        if (requestedUrl == null) {
            return null;
        }
        String url = StoredUrls.clean(requestedUrl);
        if (url == null) {
            throw ApiException.validationFailed("url", "must be an absolute http or https URL");
        }
        if (url.equals(feed.getUrl())) {
            return null;
        }
        urlChecks.requireFree(UPDATING, List.of(FeedUrlChecks.entered(url)), feed.getId());
        URI uri = URI.create(url);
        IngestTelemetry.CreateFetchTimer timer = urlChecks.startTimer();
        FeedLoader.CreateLoaded.Created download = urlChecks.download(UPDATING, uri, timer);
        timer.completed(feed.getSource().getKey(), download.bodyLength());
        return new UrlChange(url, StoredUrls.clean(download.feed().selfLink()), download);
    }

    /** The checks and the UPDATE share one short transaction under the identity lock, so no create can slip between. */
    private void changeUrl(Feed feed, UrlChange change) {
        long id = feed.getId();
        List<FeedIdentityRegistry.Candidate> candidates = FeedUrlChecks.candidates(
                change.url(), change.download().finalUrl(), change.selfUrl());
        int updated;
        try {
            updated = urlChecks.underIdentityLock(() -> {
                urlChecks.requireFree(UPDATING, candidates, id);
                return jdbc.sql(UPDATE_URL)
                        .param("url", change.url())
                        .param("selfUrl", change.selfUrl())
                        .param("now", now())
                        .param("id", id)
                        .update();
            });
        } catch (DuplicateKeyException e) {
            throw urlChecks.lostRace(UPDATING, candidates, id);
        }
        if (updated == 0) {
            throw ApiException.feedNotFound(id);
        }
        String oldUrl = HttpUrls.redact(feed.getUrl());
        String newUrl = HttpUrls.redact(change.url());
        log.atInfo()
                .setMessage(FeedService.feedText(id, "URL changed from " + oldUrl + " to " + newUrl))
                .addKeyValue(LogKeys.FEED_ID, id)
                .addKeyValue(LogKeys.URL, oldUrl)
                .addKeyValue(LogKeys.NEW_URL, newUrl)
                .log();
    }

    private void updateFields(long id, @Nullable String name, @Nullable Topic topic, @Nullable Boolean enabled) {
        if (name == null && topic == null && enabled == null) {
            return;
        }
        int updated = jdbc.sql(UPDATE_FIELDS)
                .param("name", name)
                .param("topic", topic == null ? null : topic.name())
                .param("enabled", enabled)
                .param("now", now())
                .param("id", id)
                .update();
        if (updated == 0) {
            throw ApiException.feedNotFound(id);
        }
        if (enabled != null) {
            healthGauges.refreshAfterCommit();
            logEnabled(id, enabled);
        }
        logNameAndTopic(id, name, topic);
    }

    private static void logEnabled(long id, boolean enabled) {
        log.atInfo()
                .setMessage(FeedService.feedText(id, enabled ? "enabled" : "disabled"))
                .addKeyValue(LogKeys.FEED_ID, id)
                .addKeyValue(LogKeys.ENABLED, enabled)
                .log();
    }

    private static void logNameAndTopic(long id, @Nullable String name, @Nullable Topic topic) {
        if (name == null && topic == null) {
            return;
        }
        List<String> changedFields = new ArrayList<>();
        LoggingEventBuilder event = log.atInfo().addKeyValue(LogKeys.FEED_ID, id);
        if (name != null) {
            changedFields.add("name");
            LogFields.put(event, LogKeys.NEW_NAME, LogSafe.sanitize(name, FeedService.MAX_NAME_LENGTH));
        }
        if (topic != null) {
            changedFields.add("topic");
            LogFields.put(event, LogKeys.NEW_TOPIC, topic.name());
        }
        event.setMessage(FeedService.feedText(id, "updated: " + changedFields))
                .addKeyValue(LogKeys.CHANGED_FIELDS, changedFields)
                .log();
    }

    private OffsetDateTime now() {
        return Clocks.utcNow(clock);
    }
}
