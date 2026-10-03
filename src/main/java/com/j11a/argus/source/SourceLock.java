package com.j11a.argus.source;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SourceLock {

    static final int SOURCE_LOCK_NAMESPACE = 4100;

    private final JdbcClient jdbc;

    public SourceLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Duration acquire(long sourceId) {
        int id = Math.toIntExact(sourceId);
        Boolean got = jdbc.sql("SELECT pg_try_advisory_xact_lock(:ns, :id)")
                .param("ns", SOURCE_LOCK_NAMESPACE)
                .param("id", id)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(got)) {
            return Duration.ZERO;
        }
        long start = System.nanoTime();
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:ns, :id)")
                .param("ns", SOURCE_LOCK_NAMESPACE)
                .param("id", id)
                .query(Integer.class)
                .single();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
