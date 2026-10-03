package com.j11a.argus.feed.identity;

import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.url.StoredUrls;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides whether a URL names a feed that already exists. A feed's identities are its url and its self url, compared
 * by UrlIdentity.fold. The check reads every feed (an admin-curated, small list), so it is only race-free while the
 * caller holds the identity lock: run check and write inside withIdentityLock.
 */
@Slf4j
@Component
public class FeedIdentityRegistry {

    public record Candidate(IdentityKind kind, String cleanedUrl) {
    }

    public record Conflict(IdentityKind kind, long existingFeedId) {
    }

    private record FeedRow(long id, String url, @Nullable String selfUrl) {
    }

    private final JdbcClient jdbc;
    private final FeedIdentityLock lock;
    private final Clock clock;
    private final TransactionTemplate isolatedTransaction;

    public FeedIdentityRegistry(
            JdbcClient jdbc, FeedIdentityLock lock, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.clock = clock;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One short transaction that holds the identity lock while work runs, so check-then-write is atomic. */
    @Transactional
    public <T> T withIdentityLock(Supplier<T> work) {
        lock.acquire();
        return work.get();
    }

    /** The first candidate, in list order, that matches another feed. Call it while holding the identity lock. */
    public Optional<Conflict> findConflict(List<Candidate> candidates, @Nullable Long excludeFeedId) {
        Map<String, Long> holders = new HashMap<>();
        for (FeedRow row : allFeeds()) {
            if (excludeFeedId == null || row.id() != excludeFeedId) {
                holders.putIfAbsent(UrlIdentity.fold(row.url()), row.id());
                if (row.selfUrl() != null) {
                    holders.putIfAbsent(UrlIdentity.fold(row.selfUrl()), row.id());
                }
            }
        }
        return candidates.stream()
                .filter(candidate -> holders.containsKey(UrlIdentity.fold(candidate.cleanedUrl())))
                .findFirst()
                .map(candidate -> new Conflict(candidate.kind(), holders.get(UrlIdentity.fold(candidate.cleanedUrl()))));
    }

    /** The feed, other than excludeFeedId, whose url or self url is exactly this one. */
    Optional<Long> findExactHolder(String cleanedUrl, long excludeFeedId) {
        return jdbc.sql("SELECT id FROM feed WHERE (url = :u OR self_url = :u) AND id <> :id ORDER BY id LIMIT 1")
                .param("u", cleanedUrl)
                .param("id", excludeFeedId)
                .query(Long.class)
                .optional();
    }

    /**
     * Stores the feed's self link unless it is absent, unchanged, or names another feed; a conflict is only logged
     * because a self link never disables a feed. Its own transaction, so a failure cannot touch the caller's.
     */
    public void recordSelfUrl(long feedId, @Nullable String selfLink) {
        String cleaned = StoredUrls.clean(selfLink);
        if (cleaned == null || cleaned.equals(storedSelfUrl(feedId))) {
            return;
        }
        try {
            isolatedTransaction.executeWithoutResult(status -> {
                lock.acquire();
                Optional<Conflict> conflict = findConflict(List.of(new Candidate(IdentityKind.SELF_LINK, cleaned)),
                        feedId);
                if (conflict.isPresent()) {
                    logSelfUrlConflict(feedId, conflict.get().existingFeedId(), cleaned);
                    return;
                }
                updateSelfUrl(feedId, cleaned);
            });
        } catch (DuplicateKeyException e) {
            findExactHolder(cleaned, feedId)
                    .ifPresent(existingFeedId -> logSelfUrlConflict(feedId, existingFeedId, cleaned));
        }
    }

    private @Nullable String storedSelfUrl(long feedId) {
        List<@Nullable String> stored = jdbc.sql("SELECT self_url FROM feed WHERE id = :id")
                .param("id", feedId)
                .query((rs, row) -> rs.getString(1))
                .list();
        return stored.isEmpty() ? null : stored.getFirst();
    }

    private void updateSelfUrl(long feedId, String selfUrl) {
        jdbc.sql("UPDATE feed SET self_url = :s, updated_at = :now WHERE id = :id")
                .param("s", selfUrl)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", feedId)
                .update();
    }

    private void logSelfUrlConflict(long feedId, long existingFeedId, String cleanedSelfUrl) {
        log.atInfo()
                .setMessage("Feed " + feedId + " keeps no self link: it matches feed " + existingFeedId)
                .addKeyValue(LogKeys.FEED_ID, feedId)
                .addKeyValue(LogKeys.EXISTING_FEED_ID, existingFeedId)
                .addKeyValue(LogKeys.URL, HttpUrls.redact(cleanedSelfUrl))
                .log();
    }

    private List<FeedRow> allFeeds() {
        return jdbc.sql("SELECT id, url, self_url FROM feed")
                .query((rs, row) -> new FeedRow(rs.getLong("id"), rs.getString("url"), rs.getString("self_url")))
                .list();
    }
}
