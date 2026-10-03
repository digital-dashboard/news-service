package com.j11a.argus.source;

import com.j11a.argus.url.StoredUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    // A new source is named after its key until it is renamed via PATCH or named by the seed.
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
                .param("homepage", StoredUrls.cleanPublic(siteLink))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return sources.findByKey(key).orElseThrow();
    }

    public Optional<Source> findById(long id) {
        return sources.findById(id);
    }

    @Transactional
    public SourceResponse patch(long id, PatchSourceRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validationFailed("request", "at least one field must be provided");
        }
        String name = request.name() != null ? request.name().strip() : null;
        if (request.name() != null && name.isEmpty()) {
            throw ApiException.validationFailed("name", "must not be blank");
        }
        String homepage = request.homepage() != null ? StoredUrls.cleanPublic(request.homepage()) : null;
        String country = request.country() != null ? CountryCodes.normalise(request.country()) : null;

        int updated = jdbc.sql(UPDATE)
                .param("name", name)
                .param("homepage", homepage)
                .param("country", country)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", id)
                .update();

        if (updated == 0) {
            throw new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source " + id + " does not exist.");
        }

        return queryService.get(id);
    }
}
