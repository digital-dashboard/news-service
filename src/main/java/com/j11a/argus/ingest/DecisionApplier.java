package com.j11a.argus.ingest;

import com.j11a.argus.article.ArticleEdit;
import com.j11a.argus.article.ArticleWriter;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.ingest.dedup.EntryDecision;
import com.j11a.argus.ingest.dedup.KeyedEntry;
import com.j11a.argus.ingest.dedup.Resolution;
import com.j11a.argus.ingest.dedup.UpdateReason;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DecisionApplier {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionApplier.class);

    private static final Comparator<EntryDecision> BY_GUID_KEY =
            Comparator.comparing(DecisionApplier::guidKeyOf, Comparator.nullsLast(Comparator.naturalOrder()));

    private sealed interface Outcome {
        record Inserted() implements Outcome {}
        record Updated(String reason) implements Outcome {}
        record Linked() implements Outcome {}
        record Unchanged() implements Outcome {}
        record Skipped(String reason) implements Outcome {}
    }

    private final ArticleWriter writer;

    public DecisionApplier(ArticleWriter writer) {
        this.writer = writer;
    }

    public PersistCounts apply(long sourceId, long feedId, Resolution r, Instant fetchedAt, Instant now) {
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        List<Outcome> outcomes = r.decisions().stream()
                // Sorted by GUID key: the source lock already serialises writers; this is cheap insurance
                // against lock-order deadlocks.
                .sorted(BY_GUID_KEY)
                .map(decision -> applyDecision(sourceId, feedId, decision, fetchedAt, nowUtc))
                .toList();

        int inserted = count(outcomes, Outcome.Inserted.class);
        int linked = count(outcomes, Outcome.Linked.class);
        int unchanged = count(outcomes, Outcome.Unchanged.class);
        Map<String, Integer> updated = countByReason(outcomes, Outcome.Updated.class, Outcome.Updated::reason);
        Map<String, Integer> skipped = countByReason(outcomes, Outcome.Skipped.class, Outcome.Skipped::reason);

        return new PersistCounts(inserted, updated, linked, unchanged, skipped, r.linkFallbacks());
    }

    private static int count(List<Outcome> outcomes, Class<? extends Outcome> type) {
        return (int) outcomes.stream().filter(type::isInstance).count();
    }

    private static <T extends Outcome> Map<String, Integer> countByReason(
            List<Outcome> outcomes, Class<T> type, Function<T, String> reason) {
        return outcomes.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .collect(Collectors.groupingBy(reason,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    private Outcome applyDecision(
            long sourceId, long feedId, EntryDecision decision, Instant fetchedAt, OffsetDateTime nowUtc) {
        return switch (decision) {
            case EntryDecision.Insert i -> applyInsert(sourceId, feedId, i.entry(), fetchedAt, nowUtc);
            case EntryDecision.Update u -> applyUpdate(feedId, u, nowUtc);
            case EntryDecision.Link l -> applyLink(feedId, l, nowUtc);
            case EntryDecision.Unchanged un -> applyUnchanged(feedId, un, nowUtc);
            case EntryDecision.Skip s -> new Outcome.Skipped(s.reason().tag());
        };
    }

    private Outcome applyInsert(
            long sourceId, long feedId, KeyedEntry entry, Instant fetchedAt, OffsetDateTime nowUtc) {
        NewArticle article = new NewArticle(
                sourceId,
                entry.guidKey(),
                entry.entry().guid(),
                entry.linkKey(),
                entry.entry().link(),
                entry.contentHash(),
                entry.entry().title(),
                entry.entry().excerpt(),
                entry.entry().author(),
                entry.entry().imageUrl(),
                entry.entry().categories(),
                entry.entry().publishedAt(),
                entry.entry().updatedAt(),
                entry.effectiveAt(),
                fetchedAt);
        ArticleWriter.WriteResult result = writer.insert(article, nowUtc);
        if (!result.inserted()) {
            LOG.warn("Insert conflict for source {} feed {}; existing article id {}", sourceId, feedId, result.id());
        }
        writer.link(result.id(), feedId, entry.contentHash(), nowUtc);
        return result.inserted() ? new Outcome.Inserted() : new Outcome.Updated(UpdateReason.INSERT_CONFLICT.tag());
    }

    private Outcome applyUpdate(long feedId, EntryDecision.Update u, OffsetDateTime nowUtc) {
        KeyedEntry entry = u.entry();
        replaceGuidIfNeeded(u.guidReplaced(), u.articleId(), entry, nowUtc);
        boolean dated = entry.entry().publishedAt() != null || entry.entry().updatedAt() != null;
        if (u.reason() == UpdateReason.CONTENT_CHANGED) {
            ArticleEdit edit = new ArticleEdit(
                    u.articleId(),
                    entry.linkKey(),
                    entry.entry().link(),
                    entry.entry().title(),
                    entry.entry().excerpt(),
                    entry.entry().author(),
                    entry.entry().imageUrl(),
                    entry.entry().categories(),
                    entry.entry().publishedAt(),
                    entry.entry().updatedAt(),
                    entry.effectiveAt(),
                    dated,
                    entry.contentHash());
            writer.rewriteContent(edit, nowUtc);
        } else if (u.reason() == UpdateReason.TIMESTAMP_ONLY) {
            writer.advanceTimestamps(u.articleId(), entry.entry().updatedAt(), entry.effectiveAt(), dated, nowUtc);
        }
        writer.link(u.articleId(), feedId, entry.contentHash(), nowUtc);
        return new Outcome.Updated(u.reason().tag());
    }

    private Outcome applyLink(long feedId, EntryDecision.Link l, OffsetDateTime nowUtc) {
        KeyedEntry entry = l.entry();
        replaceGuidIfNeeded(l.guidReplaced(), l.articleId(), entry, nowUtc);
        writer.link(l.articleId(), feedId, entry.contentHash(), nowUtc);
        return new Outcome.Linked();
    }

    private Outcome applyUnchanged(long feedId, EntryDecision.Unchanged un, OffsetDateTime nowUtc) {
        KeyedEntry entry = un.entry();
        replaceGuidIfNeeded(un.guidReplaced(), un.articleId(), entry, nowUtc);
        if (un.backfillFeedHash()) {
            writer.link(un.articleId(), feedId, entry.contentHash(), nowUtc);
        }
        return new Outcome.Unchanged();
    }

    private void replaceGuidIfNeeded(boolean guidReplaced, long articleId, KeyedEntry entry, OffsetDateTime nowUtc) {
        if (guidReplaced) {
            writer.replaceGuid(articleId, entry.guidKey(), entry.entry().guid(), nowUtc);
        }
    }

    private static @Nullable String guidKeyOf(EntryDecision decision) {
        return switch (decision) {
            case EntryDecision.Insert i -> i.entry().guidKey();
            case EntryDecision.Update u -> u.entry().guidKey();
            case EntryDecision.Link l -> l.entry().guidKey();
            case EntryDecision.Unchanged un -> un.entry().guidKey();
            case EntryDecision.Skip s -> null;
        };
    }
}
