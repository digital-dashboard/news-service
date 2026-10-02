package com.j11a.argus.ingest;

import com.j11a.argus.article.ArticleInserter;
import com.j11a.argus.article.InsertOutcome;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A bean of its own so the transaction proxy applies when FeedIngestService calls it. */
@Component
public class ArticlePersister {

    static final String MISSING_IDENTITY = "missing_identity";

    private record Keyed(String guidKey, ParsedEntry entry) {
    }

    private final ArticleInserter inserter;

    public ArticlePersister(ArticleInserter inserter) {
        this.inserter = inserter;
    }

    @Transactional
    public PersistCounts persist(Feed feed, List<ParsedEntry> entries, Instant fetchedAt) {
        long sourceId = feed.getSource().getId();
        // Ascending key order, so two feeds sharing entries lock rows in the same order and cannot deadlock.
        List<Keyed> keyed = entries.stream()
                .flatMap(entry -> Optional.ofNullable(EntryKeys.guidKey(entry.guid(), entry.link()))
                        .map(key -> new Keyed(key, entry))
                        .stream())
                .sorted(Comparator.comparing(Keyed::guidKey))
                .toList();
        int inserted = 0;
        for (Keyed candidate : keyed) {
            NewArticle article = toArticle(sourceId, candidate.guidKey(), candidate.entry(), fetchedAt);
            if (inserter.insert(article, feed.getId()) == InsertOutcome.INSERTED) {
                inserted++;
            }
        }
        int skipped = entries.size() - keyed.size();
        return new PersistCounts(inserted, keyed.size() - inserted,
                skipped == 0 ? Map.of() : Map.of(MISSING_IDENTITY, skipped));
    }

    private static NewArticle toArticle(long sourceId, String guidKey, ParsedEntry entry, Instant fetchedAt) {
        return new NewArticle(
                sourceId,
                guidKey,
                entry.guid(),
                EntryKeys.linkKey(entry.link()),
                entry.link(),
                entry.title(),
                entry.excerpt(),
                entry.author(),
                entry.imageUrl(),
                entry.categories(),
                entry.publishedAt(),
                entry.updatedAt(),
                EffectiveTime.of(entry.publishedAt(), entry.updatedAt(), fetchedAt),
                fetchedAt);
    }
}
