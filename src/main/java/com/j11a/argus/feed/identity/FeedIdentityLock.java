package com.j11a.argus.feed.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FeedIdentityLock {

    // Two-int advisory lock key space: namespace 4101 (4100 is the source lock), then a single global key.
    static final int FEED_IDENTITY_LOCK_NAMESPACE = 4101;
    private static final int GLOBAL_KEY = 0;

    private final JdbcClient jdbc;

    public FeedIdentityLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises every check-then-write on feed identity. The xact lock is released at commit or rollback, which is
     * PgBouncer-safe (no session state).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:ns, :key)")
                .param("ns", FEED_IDENTITY_LOCK_NAMESPACE)
                .param("key", GLOBAL_KEY)
                .query(Integer.class)
                .single();
    }
}
