package com.j11a.argus.source;

import com.j11a.argus.observability.LogFields;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SourceService {

    // A new source is named after its key until it is renamed via PATCH or named by the seed.
    private static final String NAME = "name";
    private static final String HOMEPAGE = "homepage";
    private static final String COUNTRY = "country";

    private static final String INSERT = """
            INSERT INTO source (key, name, homepage_url, created_at, updated_at)
            VALUES (:key, :key, :homepage, :now, :now)
            ON CONFLICT (key) DO NOTHING
            """;

    private static final String UPDATE = """
            UPDATE source
            SET name = COALESCE(:name, name),
                homepage_url = COALESCE(:homepage, homepage_url),
                country = COALESCE(:country, country),
                updated_at = :now
            WHERE id = :id
            """;

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final Clock clock;
    private final SourceQueryService queryService;

    public SourceService(JdbcClient jdbc, SourceRepository sources, Clock clock, SourceQueryService queryService) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.clock = clock;
        this.queryService = queryService;
    }

    /** Safe against concurrent creators: the loser of the insert race reads the winner's row. */
    @Transactional
    public Source findOrCreate(String key, @Nullable String siteLink) {
        jdbc.sql(INSERT)
                .param("key", key)
                .param(HOMEPAGE, StoredUrls.cleanPublic(siteLink))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return sources.findByKey(key).orElseThrow();
    }

    /**
     * Narrow source-key rule for feeds created without an explicit source. A site link that names an existing
     * source is trusted only when the feed's own host, the self link, or the host of a feed that source already
     * has vouches for it; otherwise the feed gets its host's source.
     */
    @Transactional
    public Source resolveAutomatic(@Nullable String siteLink, @Nullable String selfLink, URI feedUrl) {
        String siteKey = SourceResolver.keyFor(siteLink, feedUrl);
        String hostKey = SourceResolver.hostKey(feedUrl);
        Optional<Source> existing = sources.findByKey(siteKey);
        if (existing.isEmpty() || isVouched(existing.get(), siteKey, hostKey, selfLink)) {
            return findOrCreate(siteKey, siteLink);
        }
        String redactedUrl = HttpUrls.redact(feedUrl.toString());
        log.atWarn()
                .setMessage("Site link of " + redactedUrl + " names source " + siteKey
                        + " but nothing vouches for it; using " + hostKey)
                .addKeyValue(LogKeys.SOURCE_KEY, hostKey)
                .addKeyValue(LogKeys.URL, redactedUrl)
                .log();
        return findOrCreate(hostKey, null);
    }

    private boolean isVouched(Source existing, String siteKey, String hostKey, @Nullable String selfLink) {
        return siteKey.equals(hostKey)
                || siteKey.equals(SourceResolver.keyOfLink(selfLink))
                || feedHostKeysOf(existing.getId()).contains(hostKey);
    }

    private Set<String> feedHostKeysOf(long sourceId) {
        return jdbc.sql("SELECT url FROM feed WHERE source_id = :id")
                .param("id", sourceId)
                .query(String.class)
                .list()
                .stream()
                .map(url -> SourceResolver.hostKey(URI.create(url)))
                .collect(Collectors.toSet());
    }

    public Optional<Source> findById(long id) {
        return sources.findById(id);
    }

    @Transactional
    public SourceResponse patch(long id, PatchSourceRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validationFailed("request", "at least one field must be provided");
        }
        String rawName = request.name();
        String name = rawName == null ? null : rawName.strip();
        if (name != null && name.isEmpty()) {
            throw ApiException.validationFailed(NAME, "must not be blank");
        }
        String homepage = request.homepage() != null ? StoredUrls.cleanPublic(request.homepage()) : null;
        String country = request.country() != null ? CountryCodes.normalise(request.country()) : null;

        int updated = jdbc.sql(UPDATE)
                .param(NAME, name)
                .param(HOMEPAGE, homepage)
                .param(COUNTRY, country)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", id)
                .update();

        if (updated == 0) {
            throw new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source " + id + " does not exist.");
        }

        SourceResponse response = queryService.get(id);
        logPatched(response, name, homepage, country);
        return response;
    }

    private void logPatched(SourceResponse source, @Nullable String name, @Nullable String homepage,
            @Nullable String country) {
        List<String> changedFields = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        LoggingEventBuilder event = log.atInfo()
                .addKeyValue(LogKeys.SOURCE_ID, source.id())
                .addKeyValue(LogKeys.SOURCE_KEY, source.key());
        if (name != null) {
            changedFields.add(NAME);
            changes.add(NAME + "=" + name);
            LogFields.put(event, LogKeys.NEW_NAME, name);
        }
        if (homepage != null) {
            changedFields.add(HOMEPAGE);
            changes.add(HOMEPAGE + "=" + homepage);
            LogFields.put(event, LogKeys.NEW_HOMEPAGE, homepage);
        }
        if (country != null) {
            changedFields.add(COUNTRY);
            changes.add(COUNTRY + "=" + country);
            LogFields.put(event, LogKeys.NEW_COUNTRY, country);
        }
        event.setMessage("Source " + source.id() + " (" + source.key() + ") updated: " + changes)
                .addKeyValue(LogKeys.CHANGED_FIELDS, changedFields)
                .log();
    }
}
