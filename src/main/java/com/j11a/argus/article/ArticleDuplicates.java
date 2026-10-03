package com.j11a.argus.article;

import com.j11a.argus.article.ArticleCollapser.Loser;
import com.j11a.argus.url.LinkCleaner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Pairs the articles of two sources that are the same story, deciding which of each pair survives. */
public final class ArticleDuplicates {

    public record Row(long id, long sourceId, String guidKey, @Nullable String linkKey, Instant fetchedAt) {
    }

    public enum Mode {
        /** The older article survives, whichever side it is on. */
        MERGE,
        /** The target-side article always survives, because the source-side article is about to leave. */
        MOVE
    }

    private static final Comparator<Row> OLDEST = Comparator.comparing(Row::fetchedAt).thenComparingLong(Row::id);

    private ArticleDuplicates() {
    }

    /**
     * Pass 1 pairs equal guid keys. Pass 2 pairs, among the rest, a link key held by exactly one article on each side,
     * unless it is a root link or one of the homepage keys (the same guards the ingest resolver applies).
     */
    public static List<Loser> find(List<Row> source, List<Row> target, Set<String> homepageKeys, Mode mode) {
        List<Loser> losers = new ArrayList<>();
        Set<Long> paired = new HashSet<>();

        Map<String, Row> targetByGuid = new HashMap<>();
        target.forEach(row -> targetByGuid.put(row.guidKey(), row));
        for (Row s : source) {
            Row t = targetByGuid.get(s.guidKey());
            if (t != null) {
                losers.add(loser(s, t, mode));
                paired.add(s.id());
                paired.add(t.id());
            }
        }

        Map<String, List<Row>> sourceByLink = unpairedByLink(source, paired, homepageKeys);
        Map<String, List<Row>> targetByLink = unpairedByLink(target, paired, homepageKeys);
        sourceByLink.forEach((linkKey, sourceRows) -> {
            List<Row> targetRows = targetByLink.get(linkKey);
            if (sourceRows.size() == 1 && targetRows != null && targetRows.size() == 1) {
                losers.add(loser(sourceRows.getFirst(), targetRows.getFirst(), mode));
            }
        });
        return List.copyOf(losers);
    }

    private static Map<String, List<Row>> unpairedByLink(List<Row> rows, Set<Long> paired, Set<String> homepageKeys) {
        Map<String, List<Row>> byLink = new HashMap<>();
        for (Row row : rows) {
            String linkKey = row.linkKey();
            if (linkKey != null && !paired.contains(row.id()) && !homepageKeys.contains(linkKey)
                    && !LinkCleaner.isRoot(linkKey)) {
                byLink.computeIfAbsent(linkKey, key -> new ArrayList<>()).add(row);
            }
        }
        return byLink;
    }

    private static Loser loser(Row source, Row target, Mode mode) {
        boolean sourceSurvives = mode == Mode.MERGE && OLDEST.compare(source, target) < 0;
        return sourceSurvives ? new Loser(target.id(), source.id()) : new Loser(source.id(), target.id());
    }
}
