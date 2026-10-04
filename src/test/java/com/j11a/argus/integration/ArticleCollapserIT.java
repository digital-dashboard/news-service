package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.article.ArticleCollapser;
import com.j11a.argus.article.ArticleCollapser.Loser;
import com.j11a.argus.testsupport.MergeData;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArticleCollapserIT extends AbstractIntegrationTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-01T10:00:00Z");
    private static final String LINKS = "SELECT article_id, feed_id, first_seen_at, content_hash FROM article_feed ";

    private record Link(long articleId, long feedId, Instant firstSeenAt, String contentHash) {
    }

    @Autowired
    private DataSource dataSource;

    private final ArticleCollapser collapser = new ArticleCollapser();
    private MergeData data;
    private long source;
    private long feed;
    private long otherFeed;

    @BeforeEach
    void seed() {
        data = new MergeData(jdbcClient);
        source = data.source("example.test", null);
        feed = data.feed(source, "https://example.test/feed");
        otherFeed = data.feed(source, "https://example.test/other");
    }

    private List<Link> links(long articleId) {
        return jdbcClient.sql(LINKS + "WHERE article_id = :id ORDER BY feed_id").param("id", articleId)
                .query((rs, n) -> new Link(rs.getLong("article_id"), rs.getLong("feed_id"),
                        rs.getObject("first_seen_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("content_hash")))
                .list();
    }

    private int fold(List<Loser> losers, Long feedId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return collapser.foldLinks(c, losers, feedId);
        }
    }

    @Test
    void twoLosersOnOneSurvivorAndFeedFoldIntoOneLinkWithoutHittingTheRowTwice() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loserA = data.article(source, "a", null, MIDDLE);
        long loserB = data.article(source, "b", null, LATE);
        data.link(loserA, feed, MIDDLE, "hash-a");
        data.link(loserB, feed, EARLY, "hash-b");

        int written = fold(List.of(new Loser(loserA, survivor), new Loser(loserB, survivor)), null);

        assertThat(written).isEqualTo(1);
        assertThat(links(survivor)).containsExactly(new Link(survivor, feed, EARLY, "hash-b"));
    }

    @Test
    void theFoldedLinkKeepsTheHashOfTheEarliestSeenRow() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loser = data.article(source, "l", null, LATE);
        data.link(loser, feed, LATE, "hash-late");

        fold(List.of(new Loser(loser, survivor)), null);

        assertThat(links(survivor)).containsExactly(new Link(survivor, feed, LATE, "hash-late"));
    }

    @Test
    void anExistingSurvivorLinkKeepsItsHashAndTakesTheEarlierFirstSeenAt() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loser = data.article(source, "l", null, LATE);
        data.link(survivor, feed, LATE, "hash-survivor");
        data.link(loser, feed, EARLY, "hash-loser");

        fold(List.of(new Loser(loser, survivor)), null);

        assertThat(links(survivor)).containsExactly(new Link(survivor, feed, EARLY, "hash-survivor"));
    }

    @Test
    void aSurvivorLinkWithoutAHashTakesTheLosersHash() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loser = data.article(source, "l", null, LATE);
        data.link(survivor, feed, EARLY, null);
        data.link(loser, feed, LATE, "hash-loser");

        fold(List.of(new Loser(loser, survivor)), null);

        assertThat(links(survivor)).containsExactly(new Link(survivor, feed, EARLY, "hash-loser"));
    }

    @Test
    void foldingOneFeedLeavesTheOtherFeedsLinksBehind() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loser = data.article(source, "l", null, LATE);
        data.link(loser, feed, LATE, "h1");
        data.link(loser, otherFeed, LATE, "h2");

        fold(List.of(new Loser(loser, survivor)), feed);

        assertThat(links(survivor)).extracting(Link::feedId).containsExactly(feed);
        assertThat(links(loser)).extracting(Link::feedId).containsExactly(feed, otherFeed);
    }

    @Test
    void collapseFoldsTheLinksIntoTheSurvivorAndThenDeletesTheLosers() throws SQLException {
        long survivor = data.article(source, "s", null, EARLY);
        long loser = data.article(source, "l", null, LATE);
        data.link(loser, feed, LATE, "hash-loser");

        int written;
        try (Connection c = dataSource.getConnection()) {
            written = collapser.collapse(c, List.of(new Loser(loser, survivor)));
        }

        assertThat(written).isOne();
        assertThat(links(survivor)).containsExactly(new Link(survivor, feed, LATE, "hash-loser"));
        assertThat(count("SELECT count(*) FROM article WHERE id = " + loser)).isZero();
    }

    @Test
    void foldingWithoutLosersWritesNothing() throws SQLException {
        assertThat(fold(List.of(), null)).isZero();
    }

    @Test
    void deletingArticlesCascadesTheirLinksAndIgnoresAnEmptyList() throws SQLException {
        long kept = data.article(source, "k", null, EARLY);
        long gone = data.article(source, "g", null, EARLY);
        data.link(kept, feed, EARLY, "h");
        data.link(gone, feed, EARLY, "h");

        try (Connection c = dataSource.getConnection()) {
            collapser.deleteArticles(c, List.of());
            collapser.deleteArticles(c, List.of(gone));
        }

        assertThat(count("SELECT count(*) FROM article")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM article_feed")).isEqualTo(1);
    }
}
