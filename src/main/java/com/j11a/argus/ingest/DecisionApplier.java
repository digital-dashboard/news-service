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
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DecisionApplier {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionApplier.class);

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
        // Under the lock that is not needed for correctness; it is kept as cheap deadlock insurance.
        List<Outcome> outcomes = r.decisions().stream()
                .sorted(Comparator.comparing(DecisionApplier::guidKeyOf, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(decision -> applyDecision(sourceId, feedId, decision, fetchedAt, nowUtc))
                .toList();

        int inserted = (int) outcomes.stream().filter(Outcome.Inserted.class::isInstance).count();
        int linked = (int) outcomes.stream().filter(Outcome.Linked.class::isInstance).count();
        int unchanged = (int) outcomes.stream().filter(Outcome.Unchanged.class::isInstance).count();
        Map<String, Integer> updated = outcomes.stream()
                .filter(Outcome.Updated.class::isInstance)
                .map(Outcome.Updated.class::cast)
                .collect(Collectors.groupingBy(Outcome.Updated::reason,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        Map<String, Integer> skipped = outcomes.stream()
                .filter(Outcome.Skipped.class::isInstance)
                .map(Outcome.Skipped.class::cast)
                .collect(Collectors.groupingBy(Outcome.Skipped::reason,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        return new PersistCounts(inserted, updated, linked, unchanged, skipped, r.linkFallbacks());
    }

    private Outcome applyDecision(long sourceId, long feedId, EntryDecision decision, Instant fetchedAt, OffsetDateTime nowUtc) {
        return switch (decision) {
            case EntryDecision.Insert i -> applyInsert(sourceId, feedId, i.entry(), fetchedAt, nowUtc);
            case EntryDecision.Update u -> applyUpdate(feedId, u, nowUtc);
            case EntryDecision.Link l -> applyLink(feedId, l, nowUtc);
            case EntryDecision.Unchanged un -> applyUnchanged(feedId, un, nowUtc);
            case EntryDecision.Skip s -> new Outcome.Skipped(s.reason().tag());
        };
    }

    private Outcome applyInsert(long sourceId, long feedId, KeyedEntry entry, Instant fetchedAt, OffsetDateTime nowUtc) {
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
        if (result.inserted()) {
            writer.link(result.id(), feedId, entry.contentHash(), nowUtc);
            return new Outcome.Inserted();
        }
        LOG.warn("Insert conflict for source {} feed {} guidKey {}; existing article id {}",
                sourceId, feedId, entry.guidKey(), result.id());
        writer.link(result.id(), feedId, entry.contentHash(), nowUtc);
        return new Outcome.Updated(UpdateReason.INSERT_CONFLICT.tag());
    }

    private Outcome applyUpdate(long feedId, EntryDecision.Update u, OffsetDateTime nowUtc) {
        KeyedEntry entry = u.entry();
        if (u.guidReplaced()) {
            writer.replaceGuid(u.articleId(), entry.guidKey(), entry.entry().guid(), nowUtc);
        }
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
        if (l.guidReplaced()) {
            writer.replaceGuid(l.articleId(), entry.guidKey(), entry.entry().guid(), nowUtc);
        }
        writer.link(l.articleId(), feedId, entry.contentHash(), nowUtc);
        return new Outcome.Linked();
    }

    private Outcome applyUnchanged(long feedId, EntryDecision.Unchanged un, OffsetDateTime nowUtc) {
        KeyedEntry entry = un.entry();
        if (un.guidReplaced()) {
            writer.replaceGuid(un.articleId(), entry.guidKey(), entry.entry().guid(), nowUtc);
        }
        if (un.backfillFeedHash()) {
            writer.link(un.articleId(), feedId, entry.contentHash(), nowUtc);
        }
        return new Outcome.Unchanged();
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
