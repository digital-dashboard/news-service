package com.j11a.argus.source;

/** What moving one feed to another source did. sourceId is the source the feed left. */
public record MoveResult(
        long feedId,
        long sourceId,
        long targetSourceId,
        int articlesMoved,
        int articlesCopied,
        int articlesCollapsed,
        int linksFolded,
        boolean sourceDeleted) {

    static MoveResult unchanged(long feedId, long sourceId) {
        return new MoveResult(feedId, sourceId, sourceId, 0, 0, 0, 0, false);
    }
}
