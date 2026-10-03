package com.j11a.argus.source;

public record SourceMergeResponse(
        long sourceId,
        long targetSourceId,
        int feedsMoved,
        int articlesMoved,
        int articlesCollapsed,
        int linksFolded) {
}
