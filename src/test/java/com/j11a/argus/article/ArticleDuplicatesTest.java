package com.j11a.argus.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.article.ArticleCollapser.Loser;
import com.j11a.argus.article.ArticleDuplicates.Mode;
import com.j11a.argus.article.ArticleDuplicates.Row;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ArticleDuplicatesTest {

    private static final long SOURCE = 1L;
    private static final long TARGET = 2L;
    private static final Instant OLD = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant NEW = Instant.parse("2026-10-02T10:00:00Z");
    private static final String LINK = "https://example.test/a/story";

    private static Row row(long id, long sourceId, String guid, @Nullable String link, Instant fetchedAt) {
        return new Row(id, sourceId, guid, link, fetchedAt);
    }

    private static List<Loser> merge(List<Row> source, List<Row> target, Set<String> homepageKeys) {
        return ArticleDuplicates.find(source, target, homepageKeys, Mode.MERGE);
    }

    @Test
    void equalGuidKeysPairAcrossSides() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", null, NEW)), List.of(row(20, TARGET, "g1", null, OLD)), Set.of());

        assertThat(losers).containsExactly(new Loser(10, 20));
    }

    @Test
    void differentGuidAndNoLinksPairNothing() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", null, OLD)), List.of(row(20, TARGET, "g2", null, OLD)), Set.of());

        assertThat(losers).isEmpty();
    }

    @Test
    void equalLinkKeyHeldOnceOnEachSidePairs() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", LINK, NEW)), List.of(row(20, TARGET, "g2", LINK, OLD)), Set.of());

        assertThat(losers).containsExactly(new Loser(10, 20));
    }

    @Test
    void linkKeyHeldTwiceOnOneSideIsNotPaired() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", LINK, OLD), row(11, SOURCE, "g3", LINK, OLD)),
                List.of(row(20, TARGET, "g2", LINK, OLD)),
                Set.of());

        assertThat(losers).isEmpty();
    }

    @Test
    void rootLinkIsNeverPairedByLink() {
        String root = "https://example.test";

        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", root, OLD)), List.of(row(20, TARGET, "g2", root, OLD)), Set.of());

        assertThat(losers).isEmpty();
    }

    @Test
    void homepageLinkKeyIsNeverPairedByLink() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", LINK, OLD)),
                List.of(row(20, TARGET, "g2", LINK, OLD)),
                Set.of(LINK));

        assertThat(losers).isEmpty();
    }

    @Test
    void anArticlePairedByGuidDoesNotTakeAPartInTheLinkPass() {
        List<Loser> losers = merge(
                List.of(row(10, SOURCE, "g1", LINK, NEW), row(11, SOURCE, "g9", LINK, NEW)),
                List.of(row(20, TARGET, "g1", null, OLD), row(21, TARGET, "g2", LINK, OLD)),
                Set.of());

        assertThat(losers).containsExactlyInAnyOrder(new Loser(10, 20), new Loser(11, 21));
    }

    @Test
    void theOlderFetchedAtSurvivesInAMerge() {
        List<Loser> sourceOlder = merge(
                List.of(row(10, SOURCE, "g1", null, OLD)), List.of(row(20, TARGET, "g1", null, NEW)), Set.of());
        List<Loser> targetOlder = merge(
                List.of(row(10, SOURCE, "g1", null, NEW)), List.of(row(20, TARGET, "g1", null, OLD)), Set.of());

        assertThat(sourceOlder).containsExactly(new Loser(20, 10));
        assertThat(targetOlder).containsExactly(new Loser(10, 20));
    }

    @Test
    void equalFetchedAtIsBrokenByTheLowerId() {
        List<Loser> losers = merge(
                List.of(row(30, SOURCE, "g1", null, OLD)), List.of(row(20, TARGET, "g1", null, OLD)), Set.of());

        assertThat(losers).containsExactly(new Loser(30, 20));
    }

    @Test
    void aFeedMoveAlwaysKeepsTheTargetArticle() {
        List<Loser> losers = ArticleDuplicates.find(
                List.of(row(10, SOURCE, "g1", null, OLD)),
                List.of(row(20, TARGET, "g1", null, NEW)),
                Set.of(),
                Mode.MOVE);

        assertThat(losers).containsExactly(new Loser(10, 20));
    }
}
