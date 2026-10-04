package com.j11a.argus.migration;

import com.j11a.argus.article.ArticleCollapser;
import com.j11a.argus.article.ArticleCollapser.Loser;
import com.j11a.argus.ingest.ContentHash;
import com.j11a.argus.ingest.EntryKeys;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class ArticleRekeyer {

    private final ArticleCollapser collapser = new ArticleCollapser();

    private record ArticleRow(
            long id,
            long sourceId,
            String oldGuid,
            String newGuid,
            @Nullable String newLink,
            String contentHash) {
    }

    private record SourceGuidKey(long sourceId, String guidKey) {
    }

    public RekeyReport rekey(Connection c) throws SQLException {
        List<ArticleRow> rows = readArticles(c);
        int seen = rows.size();

        Map<SourceGuidKey, List<ArticleRow>> groups = new LinkedHashMap<>();
        for (ArticleRow row : rows) {
            groups.computeIfAbsent(new SourceGuidKey(row.sourceId(), row.newGuid()), k -> new ArrayList<>()).add(row);
        }

        List<ArticleRow> survivors = new ArrayList<>(groups.size());
        List<Loser> losers = new ArrayList<>();

        for (List<ArticleRow> group : groups.values()) {
            // Rows are ordered by source_id, id, so the first element has the lowest id (oldest survivor).
            ArticleRow survivor = group.getFirst();
            survivors.add(survivor);
            for (int i = 1; i < group.size(); i++) {
                ArticleRow loser = group.get(i);
                losers.add(new Loser(loser.id(), survivor.id()));
            }
        }

        int linksFolded = collapser.collapse(c, losers);

        List<Long> tempKeySurvivorIds = new ArrayList<>();
        for (ArticleRow s : survivors) {
            if (!s.oldGuid().equals(s.newGuid())) {
                tempKeySurvivorIds.add(s.id());
            }
        }
        applyTemporaryKeys(c, tempKeySurvivorIds);
        backfillSurvivors(c, survivors);

        return new RekeyReport(seen, tempKeySurvivorIds.size(), losers.size(), linksFolded);
    }

    private List<ArticleRow> readArticles(Connection c) throws SQLException {
        String sql = """
                SELECT id, source_id, guid_key, raw_guid, link, title, excerpt, categories
                FROM article ORDER BY source_id, id
                """;
        List<ArticleRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    long sourceId = rs.getLong("source_id");
                    String guidKey = rs.getString("guid_key");
                    String rawGuid = rs.getString("raw_guid");
                    String link = rs.getString("link");
                    String title = rs.getString("title");
                    String excerpt = rs.getString("excerpt");

                    Array catArray = rs.getArray("categories");
                    String[] categoriesArr = (String[]) catArray.getArray();
                    List<String> categories = Arrays.asList(categoriesArr);

                    String newGuid = EntryKeys.guidKey(rawGuid, link);
                    if (newGuid == null) {
                        newGuid = guidKey;
                    }
                    String newLink = EntryKeys.linkKey(link);
                    String hash = ContentHash.of(title, excerpt, categories);

                    rows.add(new ArticleRow(id, sourceId, guidKey, newGuid, newLink, hash));
                }
            }
        }
        return rows;
    }

    private void applyTemporaryKeys(Connection c, List<Long> tempKeySurvivorIds) throws SQLException {
        if (tempKeySurvivorIds.isEmpty()) {
            return;
        }
        // uq_article_source_guid_key is not deferrable because it is the ON CONFLICT arbiter for inserts, so
        // temporary keys avoid transient collisions.
        String sql = "UPDATE article SET guid_key = '~rekey~' || id WHERE id = ANY(?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Array array = c.createArrayOf("bigint", tempKeySurvivorIds.toArray(Long[]::new));
            ps.setArray(1, array);
            ps.executeUpdate();
        }
    }

    private void backfillSurvivors(Connection c, List<ArticleRow> survivors) throws SQLException {
        if (survivors.isEmpty()) {
            return;
        }
        String updateArticleSql = "UPDATE article SET guid_key = ?, link_key = ?, content_hash = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(updateArticleSql)) {
            for (ArticleRow s : survivors) {
                ps.setString(1, s.newGuid());
                ps.setString(2, s.newLink());
                ps.setString(3, s.contentHash());
                ps.setLong(4, s.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String updateFeedSql = "UPDATE article_feed SET content_hash = ? WHERE article_id = ?";
        try (PreparedStatement ps = c.prepareStatement(updateFeedSql)) {
            for (ArticleRow s : survivors) {
                ps.setString(1, s.contentHash());
                ps.setLong(2, s.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
