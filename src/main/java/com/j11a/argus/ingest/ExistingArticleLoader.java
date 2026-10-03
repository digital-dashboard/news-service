package com.j11a.argus.ingest;

import com.j11a.argus.ingest.dedup.ExistingArticle;
import com.j11a.argus.ingest.dedup.ExistingArticleLookup;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ExistingArticleLoader {

    private static final String SQL = """
            SELECT a.id, a.guid_key, a.link_key, a.updated_at_upstream,
                   af.article_id IS NOT NULL AS linked, af.content_hash AS feed_content_hash
            FROM article a
            LEFT JOIN article_feed af ON af.article_id = a.id AND af.feed_id = :feedId
            WHERE a.source_id = :sourceId
              AND (a.guid_key = ANY(CAST(:guidKeys AS text[])) OR a.link_key = ANY(CAST(:linkKeys AS text[])))
            """;

    private final JdbcClient jdbc;

    public ExistingArticleLoader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ExistingArticleLookup forFeed(long sourceId, long feedId) {
        return (guidKeys, linkKeys) -> {
            String[] guidArray = guidKeys.toArray(String[]::new);
            String[] linkArray = linkKeys.toArray(String[]::new);
            return jdbc.sql(SQL)
                    .param("feedId", feedId)
                    .param("sourceId", sourceId)
                    .param("guidKeys", guidArray)
                    .param("linkKeys", linkArray)
                    .query((rs, rowNum) -> {
                        long id = rs.getLong("id");
                        String guidKey = rs.getString("guid_key");
                        String linkKey = rs.getString("link_key");
                        OffsetDateTime upstream = rs.getObject("updated_at_upstream", OffsetDateTime.class);
                        Instant updatedAtUpstream = upstream != null ? upstream.toInstant() : null;
                        boolean linked = rs.getBoolean("linked");
                        String feedContentHash = rs.getString("feed_content_hash");
                        return new ExistingArticle(id, guidKey, linkKey, updatedAtUpstream, linked, feedContentHash);
                    })
                    .list();
        };
    }
}
