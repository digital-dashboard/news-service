package com.j11a.argus.source;

import com.j11a.argus.url.StoredUrls;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    // The name starts as the key until a source rename exists.
    private static final String INSERT = """
            INSERT INTO source (key, name, homepage_url, created_at, updated_at)
            VALUES (:key, :key, :homepage, :now, :now)
            ON CONFLICT (key) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final Clock clock;

    public SourceService(JdbcClient jdbc, SourceRepository sources, Clock clock) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.clock = clock;
    }

    /** Safe against concurrent creators: the loser of the insert race reads the winner's row. */
    @Transactional
    public Source findOrCreate(String key, @Nullable String siteLink) {
        jdbc.sql(INSERT)
                .param("key", key)
                .param("homepage", StoredUrls.clean(siteLink))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return sources.findByKey(key).orElseThrow();
    }
}
