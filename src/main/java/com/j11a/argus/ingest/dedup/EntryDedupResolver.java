package com.j11a.argus.ingest.dedup;

import com.j11a.argus.feed.parse.ParsedEntry;
import com.j11a.argus.ingest.ContentHash;
import com.j11a.argus.ingest.EffectiveTime;
import com.j11a.argus.ingest.EntryKeys;
import com.j11a.argus.url.LinkCleaner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class EntryDedupResolver {

    private static final Comparator<KeyedEntry> SURVIVOR_COMPARATOR = (a, b) -> {
        Instant aTime = a.entry().updatedAt();
        Instant bTime = b.entry().updatedAt();
        if (aTime == null && bTime != null) {
            return -1;
        }
        if (aTime != null && bTime == null) {
            return 1;
        }
        if (aTime != null && bTime != null) {
            int cmp = aTime.compareTo(bTime);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.position(), b.position());
    };

    public Resolution resolve(DedupInput input) {
        List<ParsedEntry> rawEntries = input.entries();
        EntryDecision[] decisions = new EntryDecision[rawEntries.size()];

        List<KeyedEntry> keyedEntries = keyEntries(rawEntries, input.fetchedAt(), decisions);
        List<KeyedEntry> survivors = collapseBatch(keyedEntries, decisions);
        Map<String, GuardType> guardedLinks = findGuardedLinks(survivors, input.homepageKey());

        Set<String> allSurvivorGuidKeys = new HashSet<>();
        Set<String> usableLinkKeys = new HashSet<>();
        collectKeys(survivors, guardedLinks, allSurvivorGuidKeys, usableLinkKeys);

        List<ExistingArticle> existing = lookupExisting(input.lookup(), allSurvivorGuidKeys, usableLinkKeys);
        Map<String, ExistingArticle> existingByGuid = new HashMap<>();
        Map<String, List<ExistingArticle>> existingByLink = new HashMap<>();
        indexExisting(existing, existingByGuid, existingByLink);

        Set<Long> claimed = new HashSet<>();
        Map<KeyedEntry, ExistingArticle> matchedByGuid = matchByGuid(survivors, existingByGuid, claimed);
        Map<LinkFallback, Integer> fallbackCounts = initialFallbackCounts();
        Map<KeyedEntry, ExistingArticle> matchedByLink = matchByLink(
                survivors, guardedLinks, existingByLink, matchedByGuid, claimed, fallbackCounts);

        for (KeyedEntry survivor : survivors) {
            decisions[survivor.position()] = decideSurvivor(survivor, matchedByGuid, matchedByLink);
        }

        return new Resolution(List.of(decisions), fallbackCounts);
    }

    private List<KeyedEntry> keyEntries(List<ParsedEntry> raw, Instant fetchedAt, EntryDecision[] decisions) {
        List<KeyedEntry> keyed = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            ParsedEntry entry = raw.get(i);
            String guidKey = EntryKeys.guidKey(entry.guid(), entry.link());
            if (guidKey == null) {
                decisions[i] = new EntryDecision.Skip(entry, SkipReason.MISSING_IDENTITY);
            } else {
                String linkKey = EntryKeys.linkKey(entry.link());
                String contentHash = ContentHash.of(entry.title(), entry.excerpt(), entry.categories());
                Instant effectiveAt = EffectiveTime.of(entry.publishedAt(), entry.updatedAt(), fetchedAt);
                keyed.add(new KeyedEntry(entry, guidKey, linkKey, contentHash, effectiveAt, i));
            }
        }
        return keyed;
    }

    private List<KeyedEntry> collapseBatch(List<KeyedEntry> entries, EntryDecision[] decisions) {
        Map<String, List<KeyedEntry>> groups = new HashMap<>();
        for (KeyedEntry e : entries) {
            groups.computeIfAbsent(e.guidKey(), key -> new ArrayList<>()).add(e);
        }
        List<KeyedEntry> survivors = new ArrayList<>();
        for (List<KeyedEntry> group : groups.values()) {
            KeyedEntry winner = Collections.max(group, SURVIVOR_COMPARATOR);
            survivors.add(winner);
            for (KeyedEntry e : group) {
                if (e != winner) {
                    decisions[e.position()] = new EntryDecision.Skip(e.entry(), SkipReason.BATCH_DUPLICATE);
                }
            }
        }
        return survivors;
    }

    private Map<String, GuardType> findGuardedLinks(List<KeyedEntry> survivors, @Nullable String homepageKey) {
        Map<String, Set<String>> guidsByLink = new HashMap<>();
        for (KeyedEntry s : survivors) {
            if (s.linkKey() != null) {
                guidsByLink.computeIfAbsent(s.linkKey(), key -> new HashSet<>()).add(s.guidKey());
            }
        }
        Map<String, GuardType> guarded = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : guidsByLink.entrySet()) {
            String linkKey = entry.getKey();
            if (linkKey.equals(homepageKey) || LinkCleaner.isRoot(linkKey)) {
                guarded.put(linkKey, GuardType.HOMEPAGE);
            } else if (entry.getValue().size() >= 2) {
                guarded.put(linkKey, GuardType.SHARED);
            }
        }
        return guarded;
    }

    private void collectKeys(
            List<KeyedEntry> survivors,
            Map<String, GuardType> guardedLinks,
            Set<String> allSurvivorGuidKeys,
            Set<String> usableLinkKeys) {
        for (KeyedEntry survivor : survivors) {
            allSurvivorGuidKeys.add(survivor.guidKey());
            if (survivor.linkKey() != null && !guardedLinks.containsKey(survivor.linkKey())) {
                usableLinkKeys.add(survivor.linkKey());
            }
        }
    }

    private List<ExistingArticle> lookupExisting(ExistingArticleLookup lookup, Set<String> guids, Set<String> links) {
        if (guids.isEmpty() && links.isEmpty()) {
            return List.of();
        }
        return lookup.find(guids, links);
    }

    private void indexExisting(
            List<ExistingArticle> articles,
            Map<String, ExistingArticle> byGuid,
            Map<String, List<ExistingArticle>> byLink) {
        for (ExistingArticle a : articles) {
            byGuid.put(a.guidKey(), a);
            if (a.linkKey() != null) {
                byLink.computeIfAbsent(a.linkKey(), key -> new ArrayList<>()).add(a);
            }
        }
    }

    private Map<KeyedEntry, ExistingArticle> matchByGuid(
            List<KeyedEntry> survivors,
            Map<String, ExistingArticle> existingByGuid,
            Set<Long> claimed) {
        Map<KeyedEntry, ExistingArticle> matches = new HashMap<>();
        for (KeyedEntry survivor : survivors) {
            ExistingArticle existing = existingByGuid.get(survivor.guidKey());
            if (existing != null) {
                matches.put(survivor, existing);
                claimed.add(existing.id());
            }
        }
        return matches;
    }

    private Map<KeyedEntry, ExistingArticle> matchByLink(
            List<KeyedEntry> survivors,
            Map<String, GuardType> guardedLinks,
            Map<String, List<ExistingArticle>> existingByLink,
            Map<KeyedEntry, ExistingArticle> matchedByGuid,
            Set<Long> claimed,
            Map<LinkFallback, Integer> fallbackCounts) {
        Map<KeyedEntry, ExistingArticle> matches = new HashMap<>();
        for (KeyedEntry survivor : survivors) {
            if (matchedByGuid.containsKey(survivor) || survivor.linkKey() == null) {
                continue;
            }
            String linkKey = survivor.linkKey();
            GuardType guard = guardedLinks.get(linkKey);
            if (guard != null) {
                fallbackCounts.merge(guard.fallback(), 1, Integer::sum);
            } else {
                List<ExistingArticle> candidates = existingByLink.getOrDefault(linkKey, List.of());
                if (candidates.size() == 1 && !claimed.contains(candidates.getFirst().id())) {
                    ExistingArticle match = candidates.getFirst();
                    matches.put(survivor, match);
                    claimed.add(match.id());
                    fallbackCounts.merge(LinkFallback.GUID_REPLACED, 1, Integer::sum);
                } else if (candidates.size() >= 2 || (candidates.size() == 1 && claimed.contains(candidates.getFirst().id()))) {
                    fallbackCounts.merge(LinkFallback.GUARDED_SHARED, 1, Integer::sum);
                }
            }
        }
        return matches;
    }

    private EntryDecision decideSurvivor(
            KeyedEntry survivor,
            Map<KeyedEntry, ExistingArticle> matchedByGuid,
            Map<KeyedEntry, ExistingArticle> matchedByLink) {
        ExistingArticle existing = matchedByGuid.get(survivor);
        boolean guidReplaced = false;
        if (existing == null) {
            existing = matchedByLink.get(survivor);
            guidReplaced = existing != null;
        }
        if (existing == null) {
            return decideUnmatched(survivor);
        }

        boolean timeAdvanced = upstreamTimeAdvanced(survivor.entry(), existing);
        if (!existing.linkedToFeed()) {
            if (timeAdvanced) {
                return new EntryDecision.Update(survivor, existing.id(), UpdateReason.TIMESTAMP_ONLY, guidReplaced, false);
            }
            return new EntryDecision.Link(survivor, existing.id(), guidReplaced);
        }

        if (existing.feedContentHash() == null) {
            return new EntryDecision.Unchanged(survivor, existing.id(), guidReplaced, true);
        }
        if (!existing.feedContentHash().equals(survivor.contentHash())) {
            return new EntryDecision.Update(survivor, existing.id(), UpdateReason.CONTENT_CHANGED, guidReplaced, true);
        }
        if (timeAdvanced) {
            return new EntryDecision.Update(survivor, existing.id(), UpdateReason.TIMESTAMP_ONLY, guidReplaced, true);
        }
        return new EntryDecision.Unchanged(survivor, existing.id(), guidReplaced, false);
    }

    /**
     * Phase 11 adds the retention cutoff and recently-purged checks here.
     */
    private EntryDecision decideUnmatched(KeyedEntry entry) {
        return new EntryDecision.Insert(entry);
    }

    private static boolean upstreamTimeAdvanced(ParsedEntry entry, ExistingArticle existing) {
        return entry.updatedAt() != null
                && (existing.updatedAtUpstream() == null || entry.updatedAt().isAfter(existing.updatedAtUpstream()));
    }

    private static Map<LinkFallback, Integer> initialFallbackCounts() {
        Map<LinkFallback, Integer> counts = new EnumMap<>(LinkFallback.class);
        counts.put(LinkFallback.GUID_REPLACED, 0);
        counts.put(LinkFallback.GUARDED_HOMEPAGE, 0);
        counts.put(LinkFallback.GUARDED_SHARED, 0);
        return counts;
    }

    private enum GuardType {
        HOMEPAGE(LinkFallback.GUARDED_HOMEPAGE),
        SHARED(LinkFallback.GUARDED_SHARED);

        private final LinkFallback fallback;

        GuardType(LinkFallback fallback) {
            this.fallback = fallback;
        }

        LinkFallback fallback() {
            return fallback;
        }
    }
}
