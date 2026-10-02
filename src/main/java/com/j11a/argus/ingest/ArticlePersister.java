package com.j11a.argus.ingest;

import com.j11a.argus.article.ArticleInserter;
import com.j11a.argus.article.InsertOutcome;
import com.j11a.argus.article.NewArticle;
import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.parse.ParsedEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A bean of its own so the transaction proxy applies when FeedIngestService calls it. */
@Component
public class ArticlePersister {

    static final String MISSING_IDENTITY = "missing_identity";

    private final ArticleInserter inserter;

    public ArticlePersister(ArticleInserter inserter) {
        this.inserter = inserter;
    }

    @Transactional
    public PersistCounts persist(Feed feed, List<ParsedEntry> entries, Instant fetchedAt) {
        long sourceId = feed.getSource().getId();
        int inserted = 0;
        int unchanged = 0;
        int skipped = 0;
        for (ParsedEntry entry : entries) {
            String guidKey = EntryKeys.guidKey(entry.guid(), entry.link());
            if (guidKey == null) {
                skipped++;
                continue;
            }
            InsertOutcome outcome = inserter.insert(toArticle(sourceId, guidKey, entry, fetchedAt), feed.getId());
            if (outcome == InsertOutcome.INSERTED) {
                inserted++;
            } else {
                unchanged++;
            }
        }
        return new PersistCounts(inserted, unchanged, skipped == 0 ? Map.of() : Map.of(MISSING_IDENTITY, skipped));
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
