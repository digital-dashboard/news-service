package com.j11a.argus.ingest.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.ContentHash;
import com.j11a.argus.ingest.EntryKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntryDedupResolverTest {

    private EntryDedupResolver resolver;
    private Instant now;
    private RecordingLookup lookup;

    @BeforeEach
    void setUp() {
        resolver = new EntryDedupResolver();
        now = Instant.parse("2026-10-03T12:00:00Z");
        lookup = new RecordingLookup();
    }

    private ParsedEntry entry(String guid, String link, String title, Instant published, Instant updated) {
        return new ParsedEntry(guid, link, title, "excerpt", null, null, List.of("news"), published, updated);
    }

    private static class RecordingLookup implements ExistingArticleLookup {
        final List<Set<String>> guidKeyCalls = new ArrayList<>();
        final List<Set<String>> linkKeyCalls = new ArrayList<>();
        List<ExistingArticle> returnArticles = new ArrayList<>();

        @Override
        public List<ExistingArticle> find(Set<String> guidKeys, Set<String> linkKeys) {
            guidKeyCalls.add(new HashSet<>(guidKeys));
            linkKeyCalls.add(new HashSet<>(linkKeys));
            return returnArticles;
        }
    }

    @Test
    void missingIdentityIsSkipped() {
        ParsedEntry invalid = entry(null, null, "No identity", null, null);
        ParsedEntry invalidLink = entry("  ", "ftp://bad-link", "No identity 2", null, null);
        DedupInput input = new DedupInput(1L, List.of(invalid, invalidLink), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions()).hasSize(2);
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Skip.class);
        assertThat(((EntryDecision.Skip) res.decisions().get(0)).reason()).isEqualTo(SkipReason.MISSING_IDENTITY);
        assertThat(res.decisions().get(1)).isInstanceOf(EntryDecision.Skip.class);
        assertThat(((EntryDecision.Skip) res.decisions().get(1)).reason()).isEqualTo(SkipReason.MISSING_IDENTITY);
        assertThat(lookup.guidKeyCalls).isEmpty();
    }

    @Test
    void collapseKeepsLatestUpdatedAtAndTieBreaksByHigherPosition() {
        Instant t1 = Instant.parse("2026-10-03T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-03T11:00:00Z");

        // pos 0 has earlier t1; pos 1 and pos 2 tie on t2; pos 2 has higher position so pos 2 survives
        ParsedEntry e0 = entry("g1", "https://example.com/1", "T0", null, t1);
        ParsedEntry e1 = entry("g1", "https://example.com/1", "T1", null, t2);
        ParsedEntry e2 = entry("g1", "https://example.com/1", "T2", null, t2);
        DedupInput input = new DedupInput(1L, List.of(e0, e1, e2), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions()).hasSize(3);
        assertThat(res.decisions().get(0)).isEqualTo(new EntryDecision.Skip(e0, SkipReason.BATCH_DUPLICATE));
        assertThat(res.decisions().get(1)).isEqualTo(new EntryDecision.Skip(e1, SkipReason.BATCH_DUPLICATE));
        assertThat(res.decisions().get(2)).isInstanceOf(EntryDecision.Insert.class);
        KeyedEntry survivor = ((EntryDecision.Insert) res.decisions().get(2)).entry();
        assertThat(survivor.position()).isEqualTo(2);
        assertThat(survivor.entry().title()).isEqualTo("T2");
    }

    @Test
    void collapseWithNullUpdatedAtCountsNullAsEarliest() {
        Instant t1 = Instant.parse("2026-10-03T10:00:00Z");
        ParsedEntry e0 = entry("g1", "https://example.com/1", "T0", null, null);
        ParsedEntry e1 = entry("g1", "https://example.com/1", "T1", null, t1);
        DedupInput input = new DedupInput(1L, List.of(e0, e1), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0)).isEqualTo(new EntryDecision.Skip(e0, SkipReason.BATCH_DUPLICATE));
        assertThat(res.decisions().get(1)).isInstanceOf(EntryDecision.Insert.class);
    }

    @Test
    void homepageLinkIsGuardedAndCountedGuardedHomepage() {
        String homeLink = "https://example.com/home";
        String homeKey = EntryKeys.linkKey(homeLink);
        ParsedEntry e = entry("g1", homeLink, "Home", null, null);
        DedupInput input = new DedupInput(1L, List.of(e), homeKey, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(lookup.linkKeyCalls.get(0)).doesNotContain(homeKey);
        assertThat(res.linkFallbacks().get(LinkFallback.GUARDED_HOMEPAGE)).isEqualTo(1);
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Insert.class);
    }

    @Test
    void rootLinkIsGuarded() {
        ParsedEntry e = entry("g1", "https://example.com/", "Root", null, null);
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(lookup.linkKeyCalls.get(0)).isEmpty();
        assertThat(res.linkFallbacks().get(LinkFallback.GUARDED_HOMEPAGE)).isEqualTo(1);
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Insert.class);
    }

    @Test
    void linkSharedByTwoGuidsIsGuardedAndCountedGuardedShared() {
        ParsedEntry e1 = entry("g1", "https://example.com/shared", "Article 1", null, null);
        ParsedEntry e2 = entry("g2", "https://example.com/shared", "Article 2", null, null);
        DedupInput input = new DedupInput(1L, List.of(e1, e2), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(lookup.linkKeyCalls.get(0)).doesNotContain("https://example.com/shared");
        assertThat(res.linkFallbacks().get(LinkFallback.GUARDED_SHARED)).isEqualTo(2);
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Insert.class);
        assertThat(res.decisions().get(1)).isInstanceOf(EntryDecision.Insert.class);
    }

    @Test
    void lookupCalledOnceNeverWithGuardedKeysAndSkippedWhenBothSetsEmpty() {
        // Both sets empty when all entries have missing identity
        DedupInput emptyInput = new DedupInput(1L, List.of(entry(null, null, "X", null, null)), null, now, lookup);
        resolver.resolve(emptyInput);
        assertThat(lookup.guidKeyCalls).isEmpty();

        // Called once with non-guarded keys
        ParsedEntry e1 = entry("g1", "https://example.com/1", "A1", null, null);
        ParsedEntry e2 = entry("g2", "https://example.com/", "A2", null, null); // root link
        DedupInput input = new DedupInput(1L, List.of(e1, e2), null, now, lookup);

        resolver.resolve(input);

        assertThat(lookup.guidKeyCalls).hasSize(1);
        assertThat(lookup.guidKeyCalls.get(0)).containsExactlyInAnyOrder("g1", "g2");
        assertThat(lookup.linkKeyCalls.get(0)).containsExactly("https://example.com/1");
    }

    @Test
    void guidMatchOnLinkedFeedSameHashIsUnchanged() {
        ParsedEntry e = entry("g1", "https://example.com/1", "Title", null, null);
        String hash = ContentHash.of("Title", "excerpt", List.of("news"));
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", null, true, hash));
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0))
                .isInstanceOf(EntryDecision.Unchanged.class);
        EntryDecision.Unchanged unchanged = (EntryDecision.Unchanged) res.decisions().get(0);
        assertThat(unchanged.articleId()).isEqualTo(100L);
        assertThat(unchanged.guidReplaced()).isFalse();
        assertThat(unchanged.backfillFeedHash()).isFalse();
    }

    @Test
    void guidMatchOnLinkedFeedNullFeedHashIsUnchangedWithBackfill() {
        ParsedEntry e = entry("g1", "https://example.com/1", "Title", null, null);
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", null, true, null));
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0))
                .isEqualTo(new EntryDecision.Unchanged(((EntryDecision.Unchanged) res.decisions().get(0)).entry(), 100L, false, true));
    }

    @Test
    void differentFeedHashGivesUpdateContentChanged() {
        ParsedEntry e = entry("g1", "https://example.com/1", "New Title", null, null);
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", null, true, "old-hash"));
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Update.class);
        EntryDecision.Update update = (EntryDecision.Update) res.decisions().get(0);
        assertThat(update.articleId()).isEqualTo(100L);
        assertThat(update.reason()).isEqualTo(UpdateReason.CONTENT_CHANGED);
        assertThat(update.wasLinked()).isTrue();
        assertThat(update.guidReplaced()).isFalse();
    }

    @Test
    void sameHashWithLaterUpdatedAtGivesUpdateTimestampOnly() {
        Instant t1 = Instant.parse("2026-10-03T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-03T11:00:00Z");
        ParsedEntry e = entry("g1", "https://example.com/1", "Title", null, t2);
        String hash = ContentHash.of("Title", "excerpt", List.of("news"));
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", t1, true, hash));
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Update.class);
        EntryDecision.Update update = (EntryDecision.Update) res.decisions().get(0);
        assertThat(update.reason()).isEqualTo(UpdateReason.TIMESTAMP_ONLY);
        assertThat(update.wasLinked()).isTrue();
    }

    @Test
    void equalOrEarlierUpdatedAtIsUnchanged() {
        Instant t1 = Instant.parse("2026-10-03T10:00:00Z");
        String hash = ContentHash.of("Title", "excerpt", List.of("news"));

        // Equal
        ParsedEntry eEqual = entry("g1", "https://example.com/1", "Title", null, t1);
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", t1, true, hash));
        Resolution res1 = resolver.resolve(new DedupInput(1L, List.of(eEqual), null, now, lookup));
        assertThat(res1.decisions().get(0)).isInstanceOf(EntryDecision.Unchanged.class);

        // Earlier
        Instant tEarlier = Instant.parse("2026-10-03T09:00:00Z");
        ParsedEntry eEarlier = entry("g1", "https://example.com/1", "Title", null, tEarlier);
        Resolution res2 = resolver.resolve(new DedupInput(1L, List.of(eEarlier), null, now, lookup));
        assertThat(res2.decisions().get(0)).isInstanceOf(EntryDecision.Unchanged.class);
    }

    @Test
    void guidMatchOnNotYetLinkedFeedGivesLinkOrUpdateTimestampOnly() {
        Instant t1 = Instant.parse("2026-10-03T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-03T11:00:00Z");
        String hash = ContentHash.of("Title", "excerpt", List.of("news"));

        // Case A: time did not advance -> Link
        ParsedEntry eSameTime = entry("g1", "https://example.com/1", "Title", null, t1);
        lookup.returnArticles = List.of(new ExistingArticle(100L, "g1", "https://example.com/1", t1, false, null));
        Resolution resA = resolver.resolve(new DedupInput(1L, List.of(eSameTime), null, now, lookup));
        assertThat(resA.decisions().get(0)).isInstanceOf(EntryDecision.Link.class);
        EntryDecision.Link link = (EntryDecision.Link) resA.decisions().get(0);
        assertThat(link.articleId()).isEqualTo(100L);
        assertThat(link.guidReplaced()).isFalse();

        // Case B: time advanced -> Update(TIMESTAMP_ONLY, wasLinked=false)
        ParsedEntry eNewer = entry("g1", "https://example.com/1", "Title", null, t2);
        Resolution resB = resolver.resolve(new DedupInput(1L, List.of(eNewer), null, now, lookup));
        assertThat(resB.decisions().get(0)).isInstanceOf(EntryDecision.Update.class);
        EntryDecision.Update update = (EntryDecision.Update) resB.decisions().get(0);
        assertThat(update.reason()).isEqualTo(UpdateReason.TIMESTAMP_ONLY);
        assertThat(update.wasLinked()).isFalse();
    }

    @Test
    void linkFallbackGivesGuidReplacedAndCountedGuidReplaced() {
        ParsedEntry e = entry("new-guid", "https://example.com/article", "Title", null, null);
        String hash = ContentHash.of("Title", "excerpt", List.of("news"));
        lookup.returnArticles = List.of(new ExistingArticle(200L, "old-guid", "https://example.com/article", null, true, hash));
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.linkFallbacks().get(LinkFallback.GUID_REPLACED)).isEqualTo(1);
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Unchanged.class);
        EntryDecision.Unchanged decision = (EntryDecision.Unchanged) res.decisions().get(0);
        assertThat(decision.guidReplaced()).isTrue();
        assertThat(decision.articleId()).isEqualTo(200L);
    }

    @Test
    void guidWinsOverLinkThatPointsAtDifferentArticle() {
        // Entry has guid g1 and link l1.
        // Article 1 has guid g1, link other-link.
        // Article 2 has guid other-guid, link l1.
        ParsedEntry e = entry("g1", "https://example.com/l1", "Title", null, null);
        lookup.returnArticles = List.of(
                new ExistingArticle(1L, "g1", "https://example.com/other", null, true, "hash1"),
                new ExistingArticle(2L, "other-guid", "https://example.com/l1", null, true, "hash2")
        );
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        // Matches Article 1 by GUID, not Article 2
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Update.class);
        EntryDecision.Update update = (EntryDecision.Update) res.decisions().get(0);
        assertThat(update.articleId()).isEqualTo(1L);
        assertThat(update.guidReplaced()).isFalse();
    }

    @Test
    void articleClaimedByGuidIsNotLinkMatchedByAnotherEntry() {
        // Entry 1 has guid g1 -> matches article 1 by guid.
        // Entry 2 has guid g2 (no match) and link l1 -> points to article 1.
        ParsedEntry e1 = entry("g1", "https://example.com/l1", "Title 1", null, null);
        ParsedEntry e2 = entry("g2", "https://example.com/l1", "Title 2", null, null);
        String hash1 = ContentHash.of("Title 1", "excerpt", List.of("news"));
        lookup.returnArticles = List.of(
                new ExistingArticle(1L, "g1", "https://example.com/l1", null, true, hash1)
        );
        DedupInput input = new DedupInput(1L, List.of(e1, e2), null, now, lookup);

        Resolution res = resolver.resolve(input);

        // e1 matches article 1
        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Unchanged.class);
        assertThat(((EntryDecision.Unchanged) res.decisions().get(0)).articleId()).isEqualTo(1L);

        // e2 cannot claim article 1, falls back to guarded_shared
        assertThat(res.decisions().get(1)).isInstanceOf(EntryDecision.Insert.class);
        assertThat(res.linkFallbacks().get(LinkFallback.GUARDED_SHARED)).isEqualTo(1);
    }

    @Test
    void twoExistingArticlesWithSameLinkKeyGiveNoMatch() {
        ParsedEntry e = entry("g-new", "https://example.com/dup-link", "Title", null, null);
        lookup.returnArticles = List.of(
                new ExistingArticle(1L, "g1", "https://example.com/dup-link", null, true, "h1"),
                new ExistingArticle(2L, "g2", "https://example.com/dup-link", null, true, "h2")
        );
        DedupInput input = new DedupInput(1L, List.of(e), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions().get(0)).isInstanceOf(EntryDecision.Insert.class);
        assertThat(res.linkFallbacks().get(LinkFallback.GUARDED_SHARED)).isEqualTo(1);
    }

    @Test
    void outputIsInDocumentOrder() {
        ParsedEntry e0 = entry("g1", "https://example.com/1", "E0", null, null);
        ParsedEntry e1 = entry(null, null, "E1-invalid", null, null);
        ParsedEntry e2 = entry("g1", "https://example.com/1", "E2-tie-winner", null, null);
        DedupInput input = new DedupInput(1L, List.of(e0, e1, e2), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions()).hasSize(3);
        assertThat(res.decisions().get(0)).isEqualTo(new EntryDecision.Skip(e0, SkipReason.BATCH_DUPLICATE));
        assertThat(res.decisions().get(1)).isEqualTo(new EntryDecision.Skip(e1, SkipReason.MISSING_IDENTITY));
        assertThat(res.decisions().get(2)).isInstanceOf(EntryDecision.Insert.class);
    }

    @Test
    void linkFallbacksAlwaysHas3Keys() {
        DedupInput input = new DedupInput(1L, List.of(), null, now, lookup);
        Resolution res = resolver.resolve(input);

        assertThat(res.linkFallbacks()).containsOnlyKeys(
                LinkFallback.GUID_REPLACED,
                LinkFallback.GUARDED_HOMEPAGE,
                LinkFallback.GUARDED_SHARED
        );
        assertThat(res.linkFallbacks().values()).containsOnly(0);
    }

    @Test
    void invariantTotalDecisionsEqualsEntriesIn() {
        ParsedEntry e0 = entry("g1", "https://example.com/1", "A", null, null);
        ParsedEntry e1 = entry(null, null, "B", null, null);
        ParsedEntry e2 = entry("g2", "https://example.com/2", "C", null, null);
        DedupInput input = new DedupInput(1L, List.of(e0, e1, e2), null, now, lookup);

        Resolution res = resolver.resolve(input);

        assertThat(res.decisions()).hasSize(input.entries().size());
    }
}
